package termuxdriver

import (
	"context"
	"fmt"
	"os"
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
	PluginName        = "termux"
	pluginVersion     = "0.1.0"
	taskHandleVersion = 1

	// Nomad's built-in drivers use this period for mutable health checks. The
	// bridge-backed task monitor uses the same client convention.
	fingerprintPeriod = 30 * time.Second
)

type TaskConfig struct {
	Command string   `codec:"command"`
	Args    []string `codec:"args"`
	WorkDir string   `codec:"work_dir"`
	Stdin   string   `codec:"stdin"`
}

type DriverState struct {
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

func New(ctx context.Context, logger hclog.Logger, client *bridge.Client) *Driver {
	return &Driver{
		ctx:    ctx,
		logger: logger.Named(PluginName),
		bridge: client,
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
		"command":  hclspec.NewAttr("command", "string", true),
		"args":     hclspec.NewAttr("args", "list(string)", false),
		"work_dir": hclspec.NewAttr("work_dir", "string", false),
		"stdin":    hclspec.NewAttr("stdin", "string", false),
	}), nil
}

func (d *Driver) Capabilities() (*drivers.Capabilities, error) {
	return &drivers.Capabilities{
		SendSignals:       false,
		Exec:              false,
		FSIsolation:       fsisolation.None,
		NetIsolationModes: []drivers.NetIsolationMode{drivers.NetIsolationModeHost},
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
			}
			fingerprint := d.fingerprint(ctx)
			select {
			case ch <- fingerprint:
			case <-ctx.Done():
				return
			}
			timer.Reset(fingerprintPeriod)
		}
	}()
	return ch, nil
}

func (d *Driver) fingerprint(ctx context.Context) *drivers.Fingerprint {
	response, err := d.bridge.Call(ctx, map[string]any{"action": "termux_status"})
	attributes := map[string]*pstructs.Attribute{
		"driver.termux": pstructs.NewBoolAttribute(true),
	}
	if response != nil {
		attributes["driver.termux.installed"] = pstructs.NewBoolAttribute(response.Installed)
		attributes["driver.termux.permission"] = pstructs.NewBoolAttribute(response.PermissionGranted)
	}
	if err != nil || response == nil || !response.OK || !response.Ready {
		description := "Termux RUN_COMMAND is not ready"
		if err != nil {
			description = err.Error()
		} else if response != nil && response.Output != "" {
			description = response.Output
		}
		return &drivers.Fingerprint{
			Attributes:        attributes,
			Health:            drivers.HealthStateUnhealthy,
			HealthDescription: description,
		}
	}
	attributes["driver.termux.ready"] = pstructs.NewBoolAttribute(true)
	return &drivers.Fingerprint{
		Attributes:        attributes,
		Health:            drivers.HealthStateHealthy,
		HealthDescription: drivers.DriverHealthy,
	}
}

func (d *Driver) StartTask(config *drivers.TaskConfig) (*drivers.TaskHandle, *drivers.DriverNetwork, error) {
	var taskConfig TaskConfig
	if err := config.DecodeDriverConfig(&taskConfig); err != nil {
		return nil, nil, fmt.Errorf("decode Termux task config: %w", err)
	}
	if taskConfig.Command == "" {
		return nil, nil, fmt.Errorf("command is required")
	}
	if config.User != "" {
		return nil, nil, fmt.Errorf("Termux tasks cannot select an Android user")
	}

	d.mu.RLock()
	_, exists := d.tasks[config.ID]
	d.mu.RUnlock()
	if exists {
		return nil, nil, fmt.Errorf("task %q already exists", config.ID)
	}

	state := DriverState{StartedAt: time.Now().UTC()}
	handle := drivers.NewTaskHandle(taskHandleVersion)
	handle.Config = config
	handle.State = drivers.TaskStateRunning
	if err := handle.SetDriverState(&state); err != nil {
		return nil, nil, fmt.Errorf("encode Termux driver state: %w", err)
	}

	request := map[string]any{
		"action":   "termux_start",
		"task_id":  config.ID,
		"command":  taskConfig.Command,
		"args":     taskConfig.Args,
		"work_dir": taskConfig.WorkDir,
		"env":      config.Env,
	}
	if taskConfig.Stdin != "" {
		request["stdin"] = taskConfig.Stdin
	}
	if _, err := d.bridge.Require(d.ctx, request); err != nil {
		return nil, nil, err
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
	d.logger.Info("started Termux command", "task_id", config.ID, "command", taskConfig.Command)
	return handle, nil, nil
}

func (d *Driver) RecoverTask(handle *drivers.TaskHandle) error {
	if handle == nil || handle.Config == nil {
		return fmt.Errorf("invalid Termux task handle")
	}
	var state DriverState
	if err := handle.GetDriverState(&state); err != nil {
		return fmt.Errorf("decode Termux driver state: %w", err)
	}
	response, err := d.bridge.Call(d.ctx, map[string]any{
		"action":  "termux_inspect",
		"task_id": handle.Config.ID,
	})
	if err != nil {
		return err
	}
	if !response.Inspected {
		return fmt.Errorf("Termux task cannot be recovered: %s", response.Output)
	}

	task := &managedTask{
		config: handle.Config,
		state:  state,
		status: drivers.TaskStateRunning,
		done:   make(chan struct{}),
	}
	d.mu.Lock()
	d.tasks[handle.Config.ID] = task
	d.mu.Unlock()
	if response.Running {
		go d.monitorTask(handle.Config.ID)
		return nil
	}
	if err := d.completeFromBridge(handle.Config.ID, response); err != nil {
		d.mu.Lock()
		delete(d.tasks, handle.Config.ID)
		d.mu.Unlock()
		return err
	}
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
		"action":  "termux_stop",
		"task_id": taskID,
		"force":   false,
	}); err != nil {
		d.mu.Lock()
		task.stopping = false
		d.mu.Unlock()
		return err
	}

	if timeout > 0 {
		timer := time.NewTimer(timeout)
		defer timer.Stop()
		select {
		case <-d.ctx.Done():
			return d.ctx.Err()
		case <-timer.C:
		}
	}
	response, err := d.inspect(taskID)
	if err != nil {
		return err
	}
	if !response.Running {
		return d.completeFromBridge(taskID, response)
	}
	if timeout > 0 {
		_, err = d.bridge.Require(d.ctx, map[string]any{
			"action":  "termux_stop",
			"task_id": taskID,
			"force":   true,
		})
		return err
	}
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
		if _, err := d.bridge.Require(d.ctx, map[string]any{
			"action":  "termux_stop",
			"task_id": taskID,
			"force":   true,
		}); err != nil {
			return err
		}
		response, err := d.inspect(taskID)
		if err != nil {
			return err
		}
		if response.Running {
			return fmt.Errorf("task %q is still stopping", taskID)
		}
		if err := d.completeFromBridge(taskID, response); err != nil {
			return err
		}
	}
	if _, err := d.bridge.Require(d.ctx, map[string]any{
		"action":  "termux_destroy",
		"task_id": taskID,
	}); err != nil {
		return err
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
			"runtime": "termux",
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
			}
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

func (d *Driver) inspect(taskID string) (*bridge.Response, error) {
	response, err := d.bridge.Call(d.ctx, map[string]any{
		"action":  "termux_inspect",
		"task_id": taskID,
	})
	if err != nil {
		return nil, err
	}
	if !response.Inspected {
		return nil, fmt.Errorf("Termux task inspection failed: %s", response.Output)
	}
	return response, nil
}

func (d *Driver) completeFromBridge(taskID string, inspected *bridge.Response) error {
	result, err := d.bridge.Require(d.ctx, map[string]any{
		"action":  "termux_result",
		"task_id": taskID,
	})
	if err != nil {
		return err
	}

	d.mu.RLock()
	task, ok := d.tasks[taskID]
	d.mu.RUnlock()
	if !ok {
		return drivers.ErrTaskNotFound
	}
	if err := writeOutput(task.config.StdoutPath, result.Stdout); err != nil {
		return fmt.Errorf("write Termux stdout: %w", err)
	}
	if err := writeOutput(task.config.StderrPath, result.Stderr); err != nil {
		return fmt.Errorf("write Termux stderr: %w", err)
	}

	exit := &drivers.ExitResult{ExitCode: inspected.ExitCode}
	if inspected.State == "failed" {
		exit.Err = fmt.Errorf("Termux command failed: %s", inspected.Output)
	}
	d.complete(taskID, exit)
	return nil
}

func writeOutput(path, output string) error {
	if path == "" || output == "" {
		return nil
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	_, err = file.WriteString(output)
	return err
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
		done := task.done
		d.mu.RUnlock()

		select {
		case <-d.ctx.Done():
			return
		case <-done:
			return
		case <-timer.C:
		}

		response, err := d.inspect(taskID)
		if err == nil && !response.Running {
			if err := d.completeFromBridge(taskID, response); err != nil {
				d.logger.Warn("collecting Termux task result failed", "task_id", taskID, "error", err)
			} else {
				return
			}
		}
		timer.Reset(fingerprintPeriod)
	}
}
