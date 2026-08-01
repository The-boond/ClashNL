package v2rayxhttp

import "testing"

func TestParseRange(t *testing.T) {
	minimum, maximum, err := parseRange("100-1000", "")
	if err != nil {
		t.Fatal(err)
	}
	if minimum != 100 || maximum != 1000 {
		t.Fatalf("unexpected range: %d-%d", minimum, maximum)
	}
	if _, _, err := parseRange("100-10", ""); err == nil {
		t.Fatal("descending range was accepted")
	}
	if _, _, err := parseRange("100-1000junk", ""); err == nil {
		t.Fatal("range with trailing garbage was accepted")
	}
}
