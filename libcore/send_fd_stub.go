//go:build !android

package libcore

func sendFdToProtect(fd int, path string) error {
	return nil
}
