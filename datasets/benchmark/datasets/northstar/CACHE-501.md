# Cache memory pressure

Fictional benchmark only. No real infrastructure or customer data.

## Situation

The fictional Catalog cache shows a rising eviction rate and lower hit ratio after a traffic change. Record memory usage, item count and eviction policy. Cache misses alone do not prove that more memory is the correct response.

## Investigation

Compare eviction counts with memory saturation and identify whether unusually large values or a changed key distribution appeared. Inspect expiry settings on a representative sample without copying customer payloads. Check whether expired entries are being refreshed too aggressively.

## Controlled response

Ask the cache owner to review bounded cache admission or expiry changes. Confirm database capacity before reducing cache retention, because additional misses increase backend load. Do not flush the entire cache during peak traffic as a diagnostic shortcut.

## Verification

Validate hit ratio, eviction rate and backend latency together after an approved change. This procedure does not authorize deleting the authoritative datastore, and it does not establish a universal expiry duration for every workload.
