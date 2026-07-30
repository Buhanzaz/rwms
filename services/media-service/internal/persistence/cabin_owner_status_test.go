package persistence

import "testing"

func TestRentalItemStatusDoesNotAcceptNew(t *testing.T) {
	t.Parallel()

	if !validRentalItemStatus("FREE") {
		t.Fatal("FREE must remain a valid rental-item status")
	}
	if validRentalItemStatus("NEW") {
		t.Fatal("NEW is a category, not a rental-item status")
	}
}
