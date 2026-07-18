/**
 * The former guard inspected a browser-only return-disposition journal.
 * That journal is no longer a source of truth, and the public asset API has
 * no equivalent return-resolution workflow. Keep untouched callers fail-closed
 * until their commands receive a server-side contract.
 */
export function hasUnresolvedReturnEquipmentDispositionLowLevel(
  _rentalItemId: string
) {
  void _rentalItemId
  return true
}
