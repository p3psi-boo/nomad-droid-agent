package bridge

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"path/filepath"
	"testing"
)

func TestClientCall(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bridge.sock")
	listener, err := net.Listen("unix", path)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		_, _ = bufio.NewReader(conn).ReadString('\n')
		_, _ = fmt.Fprintln(conn, `{"ok":true,"exit_code":0,"output":"uid=2000","uid":2000}`)
	}()

	response, err := New(path).Require(context.Background(), map[string]any{"action": "capabilities"})
	if err != nil {
		t.Fatal(err)
	}
	if response.UID != 2000 {
		t.Fatalf("UID = %d, want 2000", response.UID)
	}
}
