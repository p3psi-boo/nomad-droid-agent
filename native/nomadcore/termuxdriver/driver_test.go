package termuxdriver

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"testing"

	"github.com/bubu/nomad-droid/native/nomadcore/bridge"
	"github.com/hashicorp/go-hclog"
	"github.com/hashicorp/nomad/plugins/drivers"
)

func TestTaskLifecycleCollectsOutputAndDestroysState(t *testing.T) {
	socket, actions := startTermuxBroker(t, true)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	stdout := filepath.Join(t.TempDir(), "stdout")
	stderr := filepath.Join(t.TempDir(), "stderr")
	for _, path := range []string{stdout, stderr} {
		if err := os.WriteFile(path, nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	config := &drivers.TaskConfig{
		ID:         "task-1",
		Name:       "shell",
		AllocDir:   t.TempDir(),
		StdoutPath: stdout,
		StderrPath: stderr,
		Env:        map[string]string{"NOMAD_ALLOC_ID": "alloc-1"},
	}
	if err := config.EncodeConcreteDriverConfig(&TaskConfig{
		Command: "$PREFIX/bin/printf",
		Args:    []string{"hello"},
		WorkDir: "~/job",
		Stdin:   "input",
	}); err != nil {
		t.Fatal(err)
	}

	driver := New(ctx, hclog.NewNullLogger(), bridge.New(socket))
	handle, network, err := driver.StartTask(config)
	if err != nil {
		t.Fatal(err)
	}
	if handle == nil || network != nil {
		t.Fatalf("handle=%#v network=%#v", handle, network)
	}
	wait, err := driver.WaitTask(context.Background(), config.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := driver.StopTask(config.ID, 0, ""); err != nil {
		t.Fatal(err)
	}
	if result := <-wait; result == nil || !result.Successful() {
		t.Fatalf("exit result = %#v", result)
	}
	if got, err := os.ReadFile(stdout); err != nil || string(got) != "hello\n" {
		t.Fatalf("stdout=%q err=%v", got, err)
	}
	if got, err := os.ReadFile(stderr); err != nil || string(got) != "note\n" {
		t.Fatalf("stderr=%q err=%v", got, err)
	}
	if err := driver.DestroyTask(config.ID, false); err != nil {
		t.Fatal(err)
	}

	want := []string{"termux_start", "termux_stop", "termux_inspect", "termux_result", "termux_destroy"}
	if got := actions(); !slices.Equal(got, want) {
		t.Fatalf("actions=%v want=%v", got, want)
	}
}

func TestFingerprintRequiresVerifiedTermuxSetup(t *testing.T) {
	socket, _ := startTermuxBroker(t, false)
	driver := New(context.Background(), hclog.NewNullLogger(), bridge.New(socket))
	fingerprint := driver.fingerprint(context.Background())
	if fingerprint.Health != drivers.HealthStateUnhealthy {
		t.Fatalf("health=%q want=%q", fingerprint.Health, drivers.HealthStateUnhealthy)
	}
	if fingerprint.HealthDescription != "Set allow-external-apps=true, then run the setup test" {
		t.Fatalf("description=%q", fingerprint.HealthDescription)
	}
}

func TestRecoverRunningTask(t *testing.T) {
	socket, _ := startTermuxBroker(t, true)
	stdout := filepath.Join(t.TempDir(), "stdout")
	stderr := filepath.Join(t.TempDir(), "stderr")
	for _, path := range []string{stdout, stderr} {
		if err := os.WriteFile(path, nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	config := &drivers.TaskConfig{
		ID:         "task-recover",
		Name:       "shell",
		AllocDir:   t.TempDir(),
		StdoutPath: stdout,
		StderrPath: stderr,
	}
	if err := config.EncodeConcreteDriverConfig(&TaskConfig{Command: "sleep", Args: []string{"60"}}); err != nil {
		t.Fatal(err)
	}

	firstContext, stopFirst := context.WithCancel(context.Background())
	first := New(firstContext, hclog.NewNullLogger(), bridge.New(socket))
	handle, _, err := first.StartTask(config)
	if err != nil {
		t.Fatal(err)
	}
	stopFirst()

	recoveredContext, stopRecovered := context.WithCancel(context.Background())
	defer stopRecovered()
	recovered := New(recoveredContext, hclog.NewNullLogger(), bridge.New(socket))
	if err := recovered.RecoverTask(handle); err != nil {
		t.Fatal(err)
	}
	wait, err := recovered.WaitTask(context.Background(), config.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := recovered.StopTask(config.ID, 0, ""); err != nil {
		t.Fatal(err)
	}
	if result := <-wait; result == nil || !result.Successful() {
		t.Fatalf("exit result = %#v", result)
	}
}

func TestWaitTaskHonorsCancellation(t *testing.T) {
	socket, _ := startTermuxBroker(t, true)
	ctx, cancelDriver := context.WithCancel(context.Background())
	defer cancelDriver()
	driver := New(ctx, hclog.NewNullLogger(), bridge.New(socket))
	config := &drivers.TaskConfig{ID: "task-cancel", Name: "shell", AllocDir: t.TempDir()}
	if err := config.EncodeConcreteDriverConfig(&TaskConfig{Command: "sleep", Args: []string{"60"}}); err != nil {
		t.Fatal(err)
	}
	if _, _, err := driver.StartTask(config); err != nil {
		t.Fatal(err)
	}
	waitContext, cancelWait := context.WithCancel(context.Background())
	wait, err := driver.WaitTask(waitContext, config.ID)
	if err != nil {
		t.Fatal(err)
	}
	cancelWait()
	if result, open := <-wait; open || result != nil {
		t.Fatalf("canceled wait returned result=%#v open=%t", result, open)
	}
}

func startTermuxBroker(t *testing.T, ready bool) (string, func() []string) {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "nomad-droid-termux-test-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	path := filepath.Join(dir, "bridge.sock")
	listener, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })

	var mu sync.Mutex
	running := false
	var actions []string
	go func() {
		for {
			conn, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			go func() {
				defer conn.Close()
				var request map[string]any
				line, readErr := bufio.NewReader(conn).ReadBytes('\n')
				if readErr != nil || json.Unmarshal(line, &request) != nil {
					return
				}
				action, _ := request["action"].(string)
				mu.Lock()
				actions = append(actions, action)
				response := map[string]any{"ok": true, "exit_code": 0, "output": "ok"}
				switch action {
				case "termux_status":
					response["installed"] = true
					response["permission_granted"] = true
					response["service_available"] = true
					response["ready"] = ready
					if !ready {
						response["output"] = "Set allow-external-apps=true, then run the setup test"
					}
				case "termux_start":
					running = true
				case "termux_stop":
					running = false
				case "termux_inspect":
					response["inspected"] = true
					response["running"] = running
					if running {
						response["state"] = "running"
					} else {
						response["state"] = "exited"
					}
				case "termux_result":
					response["state"] = "exited"
					response["stdout"] = "hello\n"
					response["stderr"] = "note\n"
				}
				mu.Unlock()
				_, _ = fmt.Fprintln(conn, mustJSON(response))
			}()
		}
	}()
	return path, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), actions...)
	}
}

func mustJSON(value any) string {
	data, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return string(data)
}
