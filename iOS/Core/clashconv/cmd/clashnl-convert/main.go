package main

import (
	"fmt"
	"io"
	"os"

	"github.com/clashnl/clashconv"
)

func main() {
	var (
		content []byte
		err     error
	)
	if len(os.Args) > 1 {
		content, err = os.ReadFile(os.Args[1])
	} else {
		content, err = io.ReadAll(os.Stdin)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	converted, err := clashconv.Convert(string(content))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Print(converted)
}
