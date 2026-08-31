package androiddriver

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

func TestTaskLifecycleAndRecovery(t *testing.T) {
	socket, brokerActions := startTestBroker(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	driver := New(ctx, hclog.NewNullLogger(), bridge.New(socket))
	config := &drivers.TaskConfig{
		ID:       "task-1",
		Name:     "mobile-service",
		AllocDir: t.TempDir(),
	}
	err := config.EncodeConcreteDriverConfig(&TaskConfig{
		Package: "com.example.workload",
		Service: ".WorkService",
		Install: false,
	})
	if err != nil {
		t.Fatal(err)
	}

	handle, network, err := driver.StartTask(config)
	if err != nil {
		t.Fatal(err)
	}
	if network != nil {
		t.Fatalf("network = %#v, want nil host network", network)
	}

	recovered := New(ctx, hclog.NewNullLogger(), bridge.New(socket))
	if err := recovered.RecoverTask(handle); err != nil {
		t.Fatalf("recover task: %v", err)
	}
	wait, err := recovered.WaitTask(context.Background(), config.ID)
	if err != nil {
		t.Fatal(err)
	}
	if err := recovered.StopTask(config.ID, 0, ""); err != nil {
		t.Fatalf("stop task: %v", err)
	}
	result := <-wait
	if result == nil || !result.Successful() {
		t.Fatalf("exit result = %#v, want successful", result)
	}
	status, err := recovered.InspectTask(config.ID)
	if err != nil {
		t.Fatal(err)
	}
	if status.State != drivers.TaskStateExited {
		t.Fatalf("state = %q, want %q", status.State, drivers.TaskStateExited)
	}
	if err := recovered.DestroyTask(config.ID, false); err != nil {
		t.Fatalf("destroy task: %v", err)
	}

	wantActions := []string{"start_service", "inspect_service", "stop_service"}
	gotActions := brokerActions()
	if !slices.Equal(gotActions, wantActions) {
		t.Fatalf("broker actions = %v, want %v", gotActions, wantActions)
	}
}

func TestWaitTaskHonorsCancellation(t *testing.T) {
	socket, _ := startTestBroker(t)
	driverContext, stopDriver := context.WithCancel(context.Background())
	defer stopDriver()
	driver := New(driverContext, hclog.NewNullLogger(), bridge.New(socket))
	config := &drivers.TaskConfig{ID: "task-cancel", Name: "service", AllocDir: t.TempDir()}
	if err := config.EncodeConcreteDriverConfig(&TaskConfig{
		Package: "com.example.workload",
		Service: ".WorkService",
		Install: false,
	}); err != nil {
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

func TestResolveArtifactPathConfinesAPKToAllocation(t *testing.T) {
	root := t.TempDir()
	config := &drivers.TaskConfig{Name: "service", AllocDir: filepath.Join(root, "alloc")}
	taskDir := config.TaskDir().Dir
	if err := os.MkdirAll(taskDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(taskDir, "work.apk"), []byte("apk"), 0o600); err != nil {
		t.Fatal(err)
	}

	path, err := resolveArtifactPath(config, "work.apk")
	if err != nil {
		t.Fatal(err)
	}
	if path != filepath.Join(taskDir, "work.apk") {
		t.Fatalf("path = %q", path)
	}
	if _, err := resolveArtifactPath(config, filepath.Join(root, "outside.apk")); err == nil {
		t.Fatal("expected an artifact path outside the allocation to be rejected")
	}
}

func startTestBroker(t *testing.T) (string, func() []string) {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "nomad-droid-test-")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	path := filepath.Join(dir, "broker.sock")
	listener, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })

	var stateMu sync.Mutex
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
				stateMu.Lock()
				actions = append(actions, action)
				switch action {
				case "start_service":
					running = true
				case "stop_service", "force_stop":
					running = false
				}
				response := map[string]any{"ok": true, "exit_code": 0, "output": "ok"}
				if action == "inspect_service" {
					response["running"] = running
					response["inspected"] = true
					response["ok"] = running
				}
				stateMu.Unlock()
				_, _ = fmt.Fprintln(conn, mustJSON(response))
			}()
		}
	}()
	return path, func() []string {
		stateMu.Lock()
		defer stateMu.Unlock()
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
