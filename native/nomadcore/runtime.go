package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/bubu/nomad-droid/native/nomadcore/androiddriver"
	"github.com/bubu/nomad-droid/native/nomadcore/termuxdriver"
	"github.com/hashicorp/go-hclog"
	"github.com/hashicorp/nomad/client"
	clientconfig "github.com/hashicorp/nomad/client/config"
	"github.com/hashicorp/nomad/client/serviceregistration"
	"github.com/hashicorp/nomad/helper/pluginutils/loader"
	"github.com/hashicorp/nomad/helper/pluginutils/singleton"
	"github.com/hashicorp/nomad/nomad/structs"
	structconfig "github.com/hashicorp/nomad/nomad/structs/config"
)

type runtimeConfig struct {
	ServerAddress string `json:"server_address"`
	NodeName      string `json:"node_name"`
	Datacenter    string `json:"datacenter"`
	IntroToken    string `json:"intro_token"`
	StateDir      string `json:"state_dir"`
	AllocDir      string `json:"alloc_dir"`
	BridgeSocket  string `json:"bridge_socket"`
}

type runtimeStatus struct {
	State      string `json:"state"`
	NodeID     string `json:"node_id,omitempty"`
	NodeStatus string `json:"node_status,omitempty"`
	Server     string `json:"server,omitempty"`
	Error      string `json:"error,omitempty"`
}

type nomadRuntime struct {
	mu     sync.RWMutex
	client *client.Client
	config runtimeConfig
	state  string
	err    error
}

var mobileRuntime = &nomadRuntime{state: "stopped"}

func (r *nomadRuntime) Start(raw string) error {
	r.mu.Lock()
	if r.client != nil {
		r.mu.Unlock()
		return fmt.Errorf("Nomad client is already running")
	}

	var cfg runtimeConfig
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		r.mu.Unlock()
		return fmt.Errorf("decode runtime config: %w", err)
	}
	if err := cfg.validate(); err != nil {
		r.mu.Unlock()
		return err
	}
	if err := ensureDirectories(cfg); err != nil {
		r.mu.Unlock()
		return err
	}

	r.state = "starting"
	r.err = nil
	r.config = cfg
	r.mu.Unlock()

	logger := hclog.NewInterceptLogger(&hclog.LoggerOptions{
		Name:   "nomad-droid",
		Level:  hclog.Info,
		Output: os.Stderr,
	})

	pluginLoader, err := loader.NewPluginLoader(&loader.PluginLoaderConfig{
		Logger:    logger,
		PluginDir: filepath.Join(cfg.StateDir, "plugins"),
		InternalPlugins: map[loader.PluginID]*loader.InternalPluginConfig{
			androiddriver.PluginID(): androiddriver.PluginConfig(cfg.BridgeSocket),
			termuxdriver.PluginID():  termuxdriver.PluginConfig(cfg.BridgeSocket),
		},
		SupportedVersions: loader.AgentSupportedApiVersions,
	})
	if err != nil {
		r.mu.Lock()
		r.state = "failed"
		r.err = err
		r.mu.Unlock()
		return fmt.Errorf("create Android plugin loader: %w", err)
	}

	artifactConfig, err := clientconfig.ArtifactConfigFromAgent(structconfig.DefaultArtifactConfig())
	if err != nil {
		r.mu.Lock()
		r.state = "failed"
		r.err = err
		r.mu.Unlock()
		return fmt.Errorf("create artifact config: %w", err)
	}

	clientCfg := clientconfig.DefaultConfig()
	clientCfg.Logger = logger
	clientCfg.StateDir = cfg.StateDir
	clientCfg.AllocDir = cfg.AllocDir
	clientCfg.AllocMountsDir = filepath.Join(cfg.StateDir, "alloc-mounts")
	clientCfg.CommonPluginDir = filepath.Join(cfg.StateDir, "common-plugins")
	clientCfg.HostVolumePluginDir = filepath.Join(cfg.StateDir, "host-volume-plugins")
	clientCfg.Servers = []string{cfg.ServerAddress}
	clientCfg.IntroToken = cfg.IntroToken
	clientCfg.Artifact = artifactConfig
	clientCfg.PluginLoader = pluginLoader
	clientCfg.PluginSingletonLoader = singleton.NewSingletonLoader(logger, pluginLoader)
	clientCfg.APIListenerRegistrar = noopAPIListener{}
	clientCfg.MaxKillTimeout = 30 * time.Second
	clientCfg.Options = map[string]string{
		"driver.allowlist":      androiddriver.PluginName + "," + termuxdriver.PluginName,
		"fingerprint.allowlist": "arch,cpu,host,memory,network,nomad,signal",
	}
	clientCfg.Node = &structs.Node{
		Name:       cfg.NodeName,
		Datacenter: cfg.Datacenter,
		NodeClass:  "android",
		Attributes: map[string]string{
			"os.name":          "android",
			"kernel.name":      "android",
			"nomad.droid.base": "true",
		},
		Meta: map[string]string{"nomad-droid.version": "0.1.0"},
	}

	r.mu.RLock()
	aborted := (r.state == "stopping" || r.state == "stopped")
	r.mu.RUnlock()
	if aborted {
		return fmt.Errorf("startup aborted by user")
	}

	nomadClient, err := client.NewClient(clientCfg, nil, nil, noopServiceRegistration{}, nil)
	if err != nil {
		r.mu.Lock()
		r.state = "failed"
		r.err = err
		r.mu.Unlock()
		return fmt.Errorf("start Nomad client: %w", err)
	}

	r.mu.Lock()
	if r.state == "stopping" || r.state == "stopped" {
		r.mu.Unlock()
		_ = nomadClient.Shutdown()
		return fmt.Errorf("startup aborted by user")
	}
	r.client = nomadClient
	r.state = "running"
	r.mu.Unlock()
	return nil
}

func (r *nomadRuntime) Stop() {
	r.mu.Lock()
	c := r.client
	r.client = nil
	r.state = "stopped"
	r.mu.Unlock()

	if c != nil {
		if err := c.Shutdown(); err != nil {
			r.mu.Lock()
			r.err = err
			r.mu.Unlock()
		}
	}
}

func (r *nomadRuntime) Status() runtimeStatus {
	r.mu.RLock()
	defer r.mu.RUnlock()
	status := runtimeStatus{State: r.state, Server: r.config.ServerAddress}
	if r.err != nil {
		status.Error = r.err.Error()
	}
	if r.client != nil {
		node := r.client.Node()
		if node != nil {
			status.NodeID = node.ID
			status.NodeStatus = node.Status
		}
	}
	return status
}

func (c runtimeConfig) validate() error {
	if c.ServerAddress == "" {
		return fmt.Errorf("server_address is required")
	}
	if _, _, err := net.SplitHostPort(c.ServerAddress); err != nil {
		return fmt.Errorf("invalid server_address: %w", err)
	}
	if c.NodeName == "" || c.Datacenter == "" {
		return fmt.Errorf("node_name and datacenter are required")
	}
	if !filepath.IsAbs(c.StateDir) || !filepath.IsAbs(c.AllocDir) {
		return fmt.Errorf("state_dir and alloc_dir must be absolute")
	}
	if c.BridgeSocket == "" {
		return fmt.Errorf("bridge_socket is required")
	}
	return nil
}

func ensureDirectories(config runtimeConfig) error {
	for _, path := range []string{
		config.StateDir,
		config.AllocDir,
		filepath.Join(config.StateDir, "plugins"),
		filepath.Join(config.StateDir, "alloc-mounts"),
		filepath.Join(config.StateDir, "common-plugins"),
		filepath.Join(config.StateDir, "host-volume-plugins"),
	} {
		if err := os.MkdirAll(path, 0o700); err != nil {
			return fmt.Errorf("create runtime directory %s: %w", path, err)
		}
	}
	return nil
}

type noopAPIListener struct{}

func (noopAPIListener) Serve(context.Context, net.Listener) error { return nil }

type noopServiceRegistration struct{}

func (noopServiceRegistration) RegisterWorkload(*serviceregistration.WorkloadServices) error {
	return nil
}

func (noopServiceRegistration) RemoveWorkload(*serviceregistration.WorkloadServices) {}

func (noopServiceRegistration) UpdateWorkload(_, _ *serviceregistration.WorkloadServices) error {
	return nil
}

func (noopServiceRegistration) AllocRegistrations(string) (*serviceregistration.AllocRegistration, error) {
	return &serviceregistration.AllocRegistration{}, nil
}

func (noopServiceRegistration) UpdateTTL(_, _, _, _ string) error { return nil }

func (noopServiceRegistration) SetNodeIdentityToken(string) {}
