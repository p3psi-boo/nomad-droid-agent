package bridge

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net"
)

type Client struct {
	address string
}

type Response struct {
	OK                bool   `json:"ok"`
	ExitCode          int    `json:"exit_code"`
	Output            string `json:"output"`
	UID               int    `json:"uid,omitempty"`
	Running           bool   `json:"running,omitempty"`
	Inspected         bool   `json:"inspected,omitempty"`
	Installed         bool   `json:"installed,omitempty"`
	PermissionGranted bool   `json:"permission_granted,omitempty"`
	ServiceAvailable  bool   `json:"service_available,omitempty"`
	Ready             bool   `json:"ready,omitempty"`
	Setup             string `json:"setup,omitempty"`
	State             string `json:"state,omitempty"`
	Stdout            string `json:"stdout,omitempty"`
	Stderr            string `json:"stderr,omitempty"`
	Truncated         bool   `json:"truncated,omitempty"`
	StartedAt         int64  `json:"started_at,omitempty"`
	CompletedAt       int64  `json:"completed_at,omitempty"`
}

func New(address string) *Client {
	return &Client{address: address}
}

func (c *Client) Call(ctx context.Context, request map[string]any) (*Response, error) {
	if c.address == "" {
		return nil, fmt.Errorf("bridge socket is not configured")
	}

	conn, err := (&net.Dialer{}).DialContext(ctx, "unix", c.address)
	if err != nil {
		return nil, fmt.Errorf("connect Android bridge: %w", err)
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		if err := conn.SetDeadline(deadline); err != nil {
			return nil, fmt.Errorf("set Android bridge deadline: %w", err)
		}
	}

	data, err := json.Marshal(request)
	if err != nil {
		return nil, fmt.Errorf("encode Android bridge request: %w", err)
	}
	if _, err := fmt.Fprintf(conn, "%s\n", data); err != nil {
		return nil, fmt.Errorf("write Android bridge request: %w", err)
	}

	line, err := bufio.NewReader(conn).ReadBytes('\n')
	if err != nil {
		return nil, fmt.Errorf("read Android bridge response: %w", err)
	}
	var response Response
	if err := json.Unmarshal(line, &response); err != nil {
		return nil, fmt.Errorf("decode Android bridge response: %w", err)
	}
	return &response, nil
}

func (c *Client) Require(ctx context.Context, request map[string]any) (*Response, error) {
	response, err := c.Call(ctx, request)
	if err != nil {
		return nil, err
	}
	if !response.OK {
		return nil, fmt.Errorf("Android bridge operation failed (%d): %s", response.ExitCode, response.Output)
	}
	return response, nil
}
