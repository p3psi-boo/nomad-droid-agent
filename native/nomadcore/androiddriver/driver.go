package androiddriver

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/bubu/nomad-droid/native/nomadcore/bridge"
	"github.com/hashicorp/go-hclog"
	"github.com/hashicorp/nomad/helper/pluginutils/loader"
	"github.com/hashicorp/nomad/plugins/base"
	"github.com/hashicorp/nomad/plugins/drivers"
	"github.com/hashicorp/nomad/plugins/drivers/fsisolation"
	"github.com/hashicorp/nomad/plugins/shared/hclspec"
	pstructs "github.com/hashicorp/nomad/plugins/shared/structs"
)

const (
	PluginName        = "android"
	pluginVersion     = "0.1.0"
	taskHandleVersion = 1

	// Nomad's built-in drivers use this period for re-fingerprinting mutable
	// driver health, so the Android bridge follows the same client convention.
	fingerprintPeriod = 30 * time.Second
)

type TaskConfig struct {
	Package string `codec:"package"`
	Service string `codec:"service"`
	APKPath string `codec:"apk_path"`
	SHA256  string `codec:"sha256"`
	Install bool   `codec:"install"`
	Replace bool   `codec:"replace"`
}

type DriverState struct {
	Package   string    `codec:"package"`
	Service   string    `codec:"service"`
	StartedAt time.Time `codec:"started_at"`
}

type managedTask struct {
	config    *drivers.TaskConfig
	state     DriverState
	status    drivers.TaskState
	completed time.Time
	result    *drivers.ExitResult
	done      chan struct{}
	stopping  bool
}

type Driver struct {
	drivers.DriverSignalTaskNotSupported
	drivers.DriverExecTaskNotSupported

	ctx    context.Context
	logger hclog.Logger
	bridge *bridge.Client

	mu    sync.RWMutex
	tasks map[string]*managedTask
}

func PluginID() loader.PluginID {
	return loader.PluginID{Name: PluginName, PluginType: base.PluginTypeDriver}
}

func PluginConfig(socket string) *loader.InternalPluginConfig {
	return &loader.InternalPluginConfig{
		Config: map[string]interface{}{},
		Factory: func(ctx context.Context, logger hclog.Logger) interface{} {
			return New(ctx, logger, bridge.New(socket))
		},
	}
}

func New(ctx context.Context, logger hclog.Logger, broker *bridge.Client) *Driver {
	return &Driver{
		ctx:    ctx,
		logger: logger.Named("android"),
		bridge: broker,
		tasks:  make(map[string]*managedTask),
	}
}

func (d *Driver) PluginInfo() (*base.PluginInfoResponse, error) {
	return &base.PluginInfoResponse{
		Type:              base.PluginTypeDriver,
		PluginApiVersions: []string{drivers.ApiVersion010},
		PluginVersion:     pluginVersion,
		Name:              PluginName,
	}, nil
}

func (d *Driver) ConfigSchema() (*hclspec.Spec, error) {
	return hclspec.NewObject(map[string]*hclspec.Spec{}), nil
}

func (d *Driver) SetConfig(*base.Config) error { return nil }

func (d *Driver) TaskConfigSchema() (*hclspec.Spec, error) {
	return hclspec.NewObject(map[string]*hclspec.Spec{
		"package":  hclspec.NewAttr("package", "string", true),
		"service":  hclspec.NewAttr("service", "string", true),
		"apk_path": hclspec.NewAttr("apk_path", "string", false),
		"sha256":   hclspec.NewAttr("sha256", "string", false),
		"install": hclspec.NewDefault(
			hclspec.NewAttr("install", "bool", false),
			hclspec.NewLiteral("true"),
		),
		"replace": hclspec.NewDefault(
			hclspec.NewAttr("replace", "bool", false),
			hclspec.NewLiteral("true"),
		),
	}), nil
}

func (d *Driver) Capabilities() (*drivers.Capabilities, error) {
	return &drivers.Capabilities{
		SendSignals:          false,
		Exec:                 false,
		FSIsolation:          fsisolation.None,
		NetIsolationModes:    []drivers.NetIsolationMode{drivers.NetIsolationModeHost},
		DisableLogCollection: true,
	}, nil
}

func (d *Driver) Fingerprint(ctx context.Context) (<-chan *drivers.Fingerprint, error) {
	ch := make(chan *drivers.Fingerprint)
	go func() {
		defer close(ch)
		timer := time.NewTimer(0)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				fingerprint := d.fingerprint(ctx)
				select {
				case ch <- fingerprint:
				case <-ctx.Done():
					return
				}
				timer.Reset(fingerprintPeriod)
			}
		}
	}()
	return ch, nil
}

func (d *Driver) fingerprint(ctx context.Context) *drivers.Fingerprint {
	response, err := d.bridge.Call(ctx, map[string]any{"action": "capabilities"})
	if err != nil || !response.OK {
		description := "Shizuku broker is unavailable"
		if err != nil {
			description = err.Error()
		} else if response.Output != "" {
			description = response.Output
		}
		return &drivers.Fingerprint{
			Attributes: map[string]*pstructs.Attribute{
				"driver.android": pstructs.NewBoolAttribute(true),
			},
			Health:            drivers.HealthStateUnhealthy,
			HealthDescription: description,
		}
	}
	return &drivers.Fingerprint{
		Attributes: map[string]*pstructs.Attribute{
			"driver.android":         pstructs.NewBoolAttribute(true),
			"driver.android.shizuku": pstructs.NewBoolAttribute(true),
			"driver.android.uid":     pstructs.NewIntAttribute(int64(response.UID), ""),
		},
		Health:            drivers.HealthStateHealthy,
		HealthDescription: drivers.DriverHealthy,
	}
}

func (d *Driver) StartTask(config *drivers.TaskConfig) (*drivers.TaskHandle, *drivers.DriverNetwork, error) {
	var taskConfig TaskConfig
	if err := config.DecodeDriverConfig(&taskConfig); err != nil {
		return nil, nil, fmt.Errorf("decode Android task config: %w", err)
	}
	if taskConfig.Package == "" || taskConfig.Service == "" {
		return nil, nil, fmt.Errorf("package and service are required")
	}

	d.mu.RLock()
	_, exists := d.tasks[config.ID]
	d.mu.RUnlock()
	if exists {
		return nil, nil, fmt.Errorf("task %q already exists", config.ID)
	}

	if taskConfig.Install {
		if taskConfig.APKPath == "" || taskConfig.SHA256 == "" {
			return nil, nil, fmt.Errorf("apk_path and sha256 are required when install is enabled")
		}
		apkPath, err := resolveArtifactPath(config, taskConfig.APKPath)
		if err != nil {
			return nil, nil, err
		}
		if _, err := d.bridge.Require(d.ctx, map[string]any{
			"action":   "install_package",
			"apk_path": apkPath,
			"sha256":   taskConfig.SHA256,
			"replace":  taskConfig.Replace,
		}); err != nil {
			return nil, nil, err
		}
	}

	if _, err := d.bridge.Require(d.ctx, map[string]any{
		"action":    "start_service",
		"package":   taskConfig.Package,
		"component": taskConfig.Service,
	}); err != nil {
		return nil, nil, err
	}

	state := DriverState{Package: taskConfig.Package, Service: taskConfig.Service, StartedAt: time.Now().UTC()}
	handle := drivers.NewTaskHandle(taskHandleVersion)
	handle.Config = config
	handle.State = drivers.TaskStateRunning
	if err := handle.SetDriverState(&state); err != nil {
		return nil, nil, fmt.Errorf("encode Android driver state: %w", err)
	}

	d.mu.Lock()
	d.tasks[config.ID] = &managedTask{
		config: config,
		state:  state,
		status: drivers.TaskStateRunning,
		done:   make(chan struct{}),
	}
	d.mu.Unlock()
	go d.monitorTask(config.ID)
	d.logger.Info("started Android service", "task_id", config.ID, "package", state.Package, "service", state.Service)
	return handle, nil, nil
}

func (d *Driver) RecoverTask(handle *drivers.TaskHandle) error {
	if handle == nil || handle.Config == nil {
		return fmt.Errorf("invalid Android task handle")
	}
	var state DriverState
	if err := handle.GetDriverState(&state); err != nil {
		return fmt.Errorf("decode Android driver state: %w", err)
	}
	response, err := d.bridge.Require(d.ctx, map[string]any{
		"action":    "inspect_service",
		"package":   state.Package,
		"component": state.Service,
	})
	if err != nil || !response.Running {
		if err == nil {
			err = fmt.Errorf("Android service is not running")
		}
		return err
	}

	d.mu.Lock()
	d.tasks[handle.Config.ID] = &managedTask{
		config: handle.Config,
		state:  state,
		status: drivers.TaskStateRunning,
		done:   make(chan struct{}),
	}
	d.mu.Unlock()
	go d.monitorTask(handle.Config.ID)
	return nil
}

func (d *Driver) WaitTask(ctx context.Context, taskID string) (<-chan *drivers.ExitResult, error) {
	d.mu.RLock()
	task, ok := d.tasks[taskID]
	d.mu.RUnlock()
	if !ok {
		return nil, drivers.ErrTaskNotFound
	}
	ch := make(chan *drivers.ExitResult, 1)
	go func() {
		defer close(ch)
		select {
		case <-ctx.Done():
			return
		case <-d.ctx.Done():
			return
		case <-task.done:
			d.mu.RLock()
			result := task.result.Copy()
			d.mu.RUnlock()
			if result != nil {
				ch <- result
			}
		}
	}()
	return ch, nil
}

func (d *Driver) StopTask(taskID string, timeout time.Duration, _ string) error {
	d.mu.RLock()
	task, ok := d.tasks[taskID]
	d.mu.RUnlock()
	if !ok {
		return drivers.ErrTaskNotFound
	}
	d.mu.Lock()
	task.stopping = true
	d.mu.Unlock()
	if _, err := d.bridge.Require(d.ctx, map[string]any{
		"action":    "stop_service",
		"package":   task.state.Package,
		"component": task.state.Service,
	}); err != nil {
		d.mu.Lock()
		task.stopping = false
		d.mu.Unlock()
		return err
	}

	if timeout > 0 {
		time.Sleep(timeout)
		response, err := d.bridge.Call(d.ctx, map[string]any{
			"action":    "inspect_service",
			"package":   task.state.Package,
			"component": task.state.Service,
		})
		if err == nil && response.Running {
			if _, err := d.bridge.Require(d.ctx, map[string]any{
				"action":  "force_stop",
				"package": task.state.Package,
			}); err != nil {
				return err
			}
		}
	}
	d.complete(taskID, &drivers.ExitResult{ExitCode: 0})
	return nil
}

func (d *Driver) DestroyTask(taskID string, force bool) error {
	d.mu.RLock()
	task, ok := d.tasks[taskID]
	d.mu.RUnlock()
	if !ok {
		return drivers.ErrTaskNotFound
	}
	if task.status == drivers.TaskStateRunning {
		if !force {
			return fmt.Errorf("task %q is still running", taskID)
		}
		if err := d.StopTask(taskID, 0, ""); err != nil {
			return err
		}
	}
	d.mu.Lock()
	delete(d.tasks, taskID)
	d.mu.Unlock()
	return nil
}

func (d *Driver) InspectTask(taskID string) (*drivers.TaskStatus, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	task, ok := d.tasks[taskID]
	if !ok {
		return nil, drivers.ErrTaskNotFound
	}
	return &drivers.TaskStatus{
		ID:          taskID,
		Name:        task.config.Name,
		State:       task.status,
		StartedAt:   task.state.StartedAt,
		CompletedAt: task.completed,
		ExitResult:  task.result.Copy(),
		DriverAttributes: map[string]string{
			"package": task.state.Package,
			"service": task.state.Service,
		},
	}, nil
}

func (d *Driver) TaskStats(ctx context.Context, taskID string, interval time.Duration) (<-chan *drivers.TaskResourceUsage, error) {
	d.mu.RLock()
	_, ok := d.tasks[taskID]
	d.mu.RUnlock()
	if !ok {
		return nil, drivers.ErrTaskNotFound
	}
	ch := make(chan *drivers.TaskResourceUsage)
	go func() {
		defer close(ch)
		timer := time.NewTimer(0)
		defer timer.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
				usage := &drivers.TaskResourceUsage{
					ResourceUsage: &drivers.ResourceUsage{},
					Timestamp:     time.Now().UTC().UnixNano(),
				}
				select {
				case ch <- usage:
				case <-ctx.Done():
					return
				}
				timer.Reset(interval)
			}
		}
	}()
	return ch, nil
}

func (d *Driver) TaskEvents(ctx context.Context) (<-chan *drivers.TaskEvent, error) {
	ch := make(chan *drivers.TaskEvent)
	go func() {
		defer close(ch)
		<-ctx.Done()
	}()
	return ch, nil
}

func (d *Driver) complete(taskID string, result *drivers.ExitResult) {
	d.mu.Lock()
	defer d.mu.Unlock()
	task, ok := d.tasks[taskID]
	if !ok || task.result != nil {
		return
	}
	task.status = drivers.TaskStateExited
	task.completed = time.Now().UTC()
	task.result = result
	close(task.done)
}

func (d *Driver) monitorTask(taskID string) {
	timer := time.NewTimer(fingerprintPeriod)
	defer timer.Stop()
	for {
		d.mu.RLock()
		task, ok := d.tasks[taskID]
		if !ok || task.result != nil {
			d.mu.RUnlock()
			return
		}
		state := task.state
		done := task.done
		d.mu.RUnlock()

		select {
		case <-d.ctx.Done():
			return
		case <-done:
			return
		case <-timer.C:
		}

		response, err := d.bridge.Call(d.ctx, map[string]any{
			"action":    "inspect_service",
			"package":   state.Package,
			"component": state.Service,
		})
		if err == nil && response.Inspected && !response.Running {
			d.mu.RLock()
			stopping := task.stopping
			d.mu.RUnlock()
			result := &drivers.ExitResult{ExitCode: 1, Err: fmt.Errorf("Android service stopped outside driver control")}
			if stopping {
				result = &drivers.ExitResult{ExitCode: 0}
			}
			d.complete(taskID, result)
			return
		}
		timer.Reset(fingerprintPeriod)
	}
}

func resolveArtifactPath(config *drivers.TaskConfig, configured string) (string, error) {
	path := configured
	if !filepath.IsAbs(path) {
		path = filepath.Join(config.TaskDir().Dir, path)
	}
	path, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("resolve APK path: %w", err)
	}
	allocDir, err := filepath.Abs(config.AllocDir)
	if err != nil {
		return "", fmt.Errorf("resolve allocation path: %w", err)
	}
	relative, err := filepath.Rel(allocDir, path)
	if err != nil || relative == ".." || filepath.IsAbs(relative) || startsWithParent(relative) {
		return "", fmt.Errorf("apk_path must stay inside the allocation directory")
	}
	if info, err := os.Stat(path); err != nil || info.IsDir() {
		return "", fmt.Errorf("APK artifact is not a readable file: %s", path)
	}
	return path, nil
}

func startsWithParent(path string) bool {
	return len(path) > 3 && path[:3] == ".."+string(filepath.Separator)
}
