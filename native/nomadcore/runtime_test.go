package main

import (
	"path/filepath"
	"testing"
)

func TestRuntimeConfigValidate(t *testing.T) {
	root := t.TempDir()
	config := runtimeConfig{
		ServerAddress: "127.0.0.1:4647",
		NodeName:      "pixel-01",
		Datacenter:    "android",
		StateDir:      filepath.Join(root, "state"),
		AllocDir:      filepath.Join(root, "alloc"),
		BridgeSocket:  filepath.Join(root, "bridge.sock"),
	}
	if err := config.validate(); err != nil {
		t.Fatal(err)
	}
}

func TestRuntimeConfigRejectsServerWithoutPort(t *testing.T) {
	config := runtimeConfig{ServerAddress: "nomad.example"}
	if err := config.validate(); err == nil {
		t.Fatal("expected invalid server address")
	}
}
