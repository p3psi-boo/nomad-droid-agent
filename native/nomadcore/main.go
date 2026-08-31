package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"unsafe"
)

//export NomadStart
func NomadStart(raw *C.char) C.int {
	if raw == nil {
		return 2
	}
	if err := mobileRuntime.Start(C.GoString(raw)); err != nil {
		return 1
	}
	return 0
}

//export NomadStop
func NomadStop() {
	mobileRuntime.Stop()
}

//export NomadStatus
func NomadStatus() *C.char {
	data, err := json.Marshal(mobileRuntime.Status())
	if err != nil {
		return C.CString(`{"state":"failed","error":"encode status"}`)
	}
	return C.CString(string(data))
}

//export NomadFree
func NomadFree(value *C.char) {
	C.free(unsafe.Pointer(value))
}

func main() {}
