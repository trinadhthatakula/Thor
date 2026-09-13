# Service Queue Latency Baseline

## Environment

- Host ADB serial: `emulator-5554`
- Device-reported serial: `EMULATOR37X2X7X0`
- API level: 37
- Build type: `userdebug`
- Model: `sdk_gphone16k_arm64`
- Fingerprint: `google/sdk_gphone16k_arm64/emu64a16k:17/CE2A.260420.019/15611780:userdebug/dev-keys`
- Build: Foss debug, WorkManager implementation
- Baseline source before evidence-only hooks: `591c96ec717c6b515256c31ee42fe265f785e0f8`
- Measurement APK SHA-256: `f33b1dbd69a415c3fe98f898edde9298ba696190c66234aee87e939a4a4d1686`
- Installed package/version: `com.valhalla.thor.debug`, `1.95.2` (`versionCode=1952`)
- Privilege mode: Shizuku
- Benchmark app: Alpha (`io.github.vvb2060.magisk`, version `e8a58776-alpha`, version code `30700`), one base APK
- Export settings: `.apk` to `Downloads/Thor`
- Privilege sweep: one-target `ReInstall` of the benchmark app

The same emulator, build type, benchmark app, destination, and sweep target must be used for the Task 19 post-cutover comparison.

## Measurement boundaries

All timestamps below come from `SystemClock.elapsedRealtimeNanos()` in the temporary debug-only `ServiceQueueLatencyProbe`. Each run has one in-memory opaque UUID and exactly one marker for each event. Marker output contains only the operation class, opaque run ID, event name, and monotonic timestamp.

- **tap**: the final Export or confirmed Reinstall action callback, before coroutine scheduling or launch work.
- **logger visible**: the first draw of the active export/sweep progress surface.
- **Room accepted**: successful awaited WorkManager enqueue for Export; return from Thor's Room `createOrFindEquivalent` transaction for Privilege sweep.
- **foreground/admitted**: successful `setForeground` return for Export; initial visible progress-notification publication for the deliberately non-foreground sweep worker.
- **first operation**: immediately before the first staged export-byte write; immediately before the first sweep target is dispatched to `PrivilegeSweepItemExecutor`.

Latencies are event timestamp minus `tap`. Percentiles use linear interpolation over the 20 sorted observations; p50 is the median (mean of observations 10 and 11). Times are rounded to three decimal places only in the summaries; raw nanosecond timestamps are retained below.

## Summary

| Class | Run count | Logger p50 | Room p50 | Foreground/admission p50 | First-operation p50 |
|---|---:|---:|---:|---:|---:|
| Export | 20 | 17.407 ms | 25.069 ms | 37.008 ms | 41.496 ms |
| Privilege sweep | 20 | 35.718 ms | 3.956 ms | 44.829 ms | 47.758 ms |

## Distributions

| Class | Milestone | Min | p25 | p50 | p75 | p95 | Max |
|---|---|---:|---:|---:|---:|---:|---:|
| Export | Logger visible | 12.646 | 16.341 | 17.407 | 19.780 | 28.605 | 32.193 |
| Export | Room accepted | 12.714 | 23.549 | 25.069 | 28.672 | 39.390 | 39.404 |
| Export | Foreground/admitted | 28.716 | 34.874 | 37.008 | 41.412 | 44.941 | 51.003 |
| Export | First operation | 31.147 | 39.571 | 41.496 | 45.258 | 52.464 | 54.857 |
| Privilege sweep | Logger visible | 14.787 | 28.257 | 35.718 | 37.375 | 40.299 | 45.538 |
| Privilege sweep | Room accepted | 2.933 | 3.691 | 3.956 | 4.498 | 28.878 | 32.639 |
| Privilege sweep | Foreground/admitted | 37.212 | 42.092 | 44.829 | 46.788 | 49.839 | 53.053 |
| Privilege sweep | First operation | 40.716 | 44.467 | 47.758 | 49.407 | 53.360 | 55.801 |

All values in the distribution table are milliseconds after `tap`.

### Raw bucket counts

| Class | Tap-relative bucket | Logger visible | Room accepted | Foreground/admitted | First operation |
|---|---|---:|---:|---:|---:|
| Export | 0–<10 ms | 0 | 0 | 0 | 0 |
| Export | 10–<20 ms | 16 | 3 | 0 | 0 |
| Export | 20–<30 ms | 3 | 12 | 1 | 0 |
| Export | 30–<40 ms | 1 | 5 | 13 | 7 |
| Export | 40–<50 ms | 0 | 0 | 5 | 11 |
| Export | 50–<60 ms | 0 | 0 | 1 | 2 |
| Export | 60–<70 ms | 0 | 0 | 0 | 0 |
| Export | 70–<80 ms | 0 | 0 | 0 | 0 |
| Export | >=80 ms | 0 | 0 | 0 | 0 |
| Privilege sweep | 0–<10 ms | 0 | 16 | 0 | 0 |
| Privilege sweep | 10–<20 ms | 2 | 0 | 0 | 0 |
| Privilege sweep | 20–<30 ms | 4 | 3 | 0 | 0 |
| Privilege sweep | 30–<40 ms | 12 | 1 | 4 | 0 |
| Privilege sweep | 40–<50 ms | 2 | 0 | 15 | 16 |
| Privilege sweep | 50–<60 ms | 0 | 0 | 1 | 4 |
| Privilege sweep | 60–<70 ms | 0 | 0 | 0 | 0 |
| Privilege sweep | 70–<80 ms | 0 | 0 | 0 | 0 |
| Privilege sweep | >=80 ms | 0 | 0 | 0 | 0 |

Each milestone column totals 20 observations for each class. The lower sweep Room latency is expected: its Room insert can complete before Compose draws the queued logger. Export's accepted event waits for WorkManager's enqueue operation; in two runs that operation also completed before the first drawn logger frame.

## Retained raw observations

Columns after the run ID are absolute `elapsedRealtimeNanos` timestamps. Rows are in collection order; Export 01–20 were collected before Sweep 01–20.

### Export

| Run | Run ID | Tap | Logger visible | Room accepted | Foreground/admitted | First operation |
|---|---|---:|---:|---:|---:|---:|
| Export 01 | `da7477c2-d1e6-4e4a-bf2b-8c8a0d49178a` | 9598125511243 | 9598142728159 | 9598164900993 | 9598170132993 | 9598174624951 |
| Export 02 | `9cc51dda-72e7-4c2e-973c-3ac6da583ede` | 9609003670998 | 9609020460706 | 9609025955998 | 9609037681081 | 9609041865164 |
| Export 03 | `10ad2d9c-d62d-4152-840a-e95e692f60ee` | 9619835968086 | 9619855423795 | 9619860836295 | 9619864683961 | 9619867114920 |
| Export 04 | `01ef7ee0-28a0-4f1a-aa00-a8d7a356dff4` | 9630642174550 | 9630656191050 | 9630670321633 | 9630681387300 | 9630684588133 |
| Export 05 | `5ce7e998-c71f-4972-8208-736db91f99e3` | 9641483015305 | 9641502794347 | 9641508097597 | 9641514342222 | 9641518884597 |
| Export 06 | `92ce86fc-9242-4226-9774-37c37d7ce79b` | 9652304428852 | 9652321181893 | 9652334674935 | 9652346589227 | 9652351036477 |
| Export 07 | `0e28176e-e7f1-4511-b6fc-d42b63bb2dda` | 9663194171524 | 9663210109107 | 9663230045649 | 9663235334440 | 9663238979690 |
| Export 08 | `10c4ef6d-a392-4b93-9d03-b23cf573078b` | 9674040569445 | 9674058166487 | 9674065160445 | 9674083879529 | 9674087215279 |
| Export 09 | `90d9a111-ef3b-4a1c-b2dc-5f9ee28ca289` | 9684876686367 | 9684889332867 | 9684901742492 | 9684913363159 | 9684917265367 |
| Export 10 | `32cda02e-facd-414e-951b-458257a63dc3` | 9695752460622 | 9695769677789 | 9695776384164 | 9695787705997 | 9695792156331 |
| Export 11 | `20a6b59d-74ec-40f5-8f0c-041021517c2c` | 9706635903294 | 9706652378711 | 9706658329628 | 9706669932878 | 9706675108878 |
| Export 12 | `0710f9ed-3044-4100-a018-2abc7392eae2` | 9717474182591 | 9717502599216 | 9717486896924 | 9717511520883 | 9717517430049 |
| Export 13 | `686b51d3-34dc-4de4-935a-869e015cbd84` | 9728320052221 | 9728352245555 | 9728333832096 | 9728359622013 | 9728364525930 |
| Export 14 | `bb59483d-2e96-4ce0-9e6a-774185bf607c` | 9739185690101 | 9739203752851 | 9739210287810 | 9739236693351 | 9739240547101 |
| Export 15 | `0ba2aa5c-761c-4777-b2f3-056394bd04cb` | 9750062000565 | 9750075193857 | 9750097435398 | 9750101272690 | 9750104565482 |
| Export 16 | `7182ded3-9b15-4e02-a31c-6b83c769b9d2` | 9760949596737 | 9760969378904 | 9760975296487 | 9760986102195 | 9760989290029 |
| Export 17 | `9df59317-f0a1-405a-b04b-7dd9400680e0` | 9771793673117 | 9771808725825 | 9771833077575 | 9771837908784 | 9771846010992 |
| Export 18 | `fe78c240-7978-47b2-9224-7cb1cba51062` | 9782650623997 | 9782668788039 | 9782676277539 | 9782686932039 | 9782691058956 |
| Export 19 | `0f3afa72-b663-4b8c-8eef-7e14cf9be083` | 9793447640669 | 9793469720794 | 9793474993502 | 9793481079877 | 9793483212794 |
| Export 20 | `f1b1a0d3-add9-45c9-903a-c8337c7ed4fa` | 9804228498841 | 9804253360924 | 9804241313341 | 9804263654799 | 9804268512674 |

### Privilege sweep

| Run | Run ID | Tap | Logger visible | Room accepted | Foreground/admitted | First operation |
|---|---|---:|---:|---:|---:|---:|
| Sweep 01 | `f13c44d4-6f59-4cb7-b8ae-58bfb1e3ce60` | 9894627108301 | 9894659827509 | 9894630896009 | 9894665296051 | 9894669170842 |
| Sweep 02 | `aa376070-81cb-45b5-b7cc-981f511c1a3c` | 9907477582557 | 9907513341557 | 9907481480765 | 9907520907807 | 9907526370307 |
| Sweep 03 | `558e21cb-cc3e-4604-9531-8b6d45af3c94` | 9920341274521 | 9920366082521 | 9920373913355 | 9920390279480 | 9920391853480 |
| Sweep 04 | `c485a914-cd7c-4f71-9ea0-e8a8d732ad51` | 9933219313069 | 9933256577986 | 9933223004569 | 9933263935194 | 9933265693402 |
| Sweep 05 | `e34fdbf2-8162-4bdc-a421-c4a5474911cd` | 9946077488450 | 9946113166200 | 9946081800450 | 9946117061950 | 9946120113533 |
| Sweep 06 | `c0d616f5-a24e-453b-8b1a-eb9274f2e92d` | 9958881441290 | 9958913238540 | 9958885507623 | 9958918817540 | 9958922157206 |
| Sweep 07 | `dae166d5-242b-442e-9b39-9193e71bef29` | 9971517412671 | 9971554616504 | 9971520707546 | 9971563718921 | 9971565149296 |
| Sweep 08 | `410e80b3-a03d-4338-8718-2ac917708939` | 9984330487177 | 9984367158343 | 9984334314718 | 9984375616510 | 9984378421177 |
| Sweep 09 | `31316a89-41a1-4536-b788-262bd96bfa7c` | 9997132679974 | 9997171974891 | 9997136058849 | 9997181817474 | 9997185911516 |
| Sweep 10 | `3c44436a-f527-4cf1-be3e-800dc5b9ff20` | 10009997292439 | 10010037315689 | 10010001500314 | 10010042327981 | 10010045071397 |
| Sweep 11 | `33209f94-78a0-4920-87d9-741f5b030ad9` | 10022874289528 | 10022896164445 | 10022902969070 | 10022917964487 | 10022920275153 |
| Sweep 12 | `7d5be2ec-94a5-4247-853c-a0a84151edce` | 10035775709035 | 10035791960368 | 10035800700493 | 10035822015743 | 10035822873618 |
| Sweep 13 | `f2b907a6-db93-4398-8973-236992b01d04` | 10048661592207 | 10048698176416 | 10048665519082 | 10048705305374 | 10048710003416 |
| Sweep 14 | `4a4b8bde-1c11-4cdf-a650-c3e839e7ff79` | 10061442673588 | 10061457460547 | 10061465522422 | 10061484521130 | 10061485362755 |
| Sweep 15 | `e466ba7d-bc8b-42b2-bc47-4b7e40ce71a1` | 10074270040428 | 10074308360136 | 10074273681761 | 10074319710053 | 10074321799345 |
| Sweep 16 | `a01dbc58-0854-4b55-aca7-6b95d24fdeef` | 10087137026726 | 10087165803892 | 10087140715976 | 10087174238351 | 10087178618726 |
| Sweep 17 | `9623baca-b958-473d-a701-4f8ac13e3b92` | 10100079179607 | 10100116883649 | 10100084177565 | 10100125668149 | 10100128468482 |
| Sweep 18 | `0f62df46-bac1-478e-b426-4563560b81bd` | 10112995904071 | 10113031202988 | 10113000235446 | 10113038077696 | 10113040964155 |
| Sweep 19 | `3d714877-3733-45ad-a683-eb7d70e6aac7` | 10125728846244 | 10125774384452 | 10125732832119 | 10125781899661 | 10125784647619 |
| Sweep 20 | `8fb3f115-4b4b-4436-bac3-580bee407bef` | 10138587421625 | 10138614119542 | 10138590354542 | 10138635108292 | 10138637182000 |

## Run accounting

One complete warm-up was intentionally excluded before each class:

- Export warm-up: `8ad54145-81d0-4179-abb0-ee087b8d64ee`
- Privilege-sweep warm-up: `d6e12ad8-9a64-4c4c-bfa2-805356823137`

After warm-up, 20 sequential candidate runs were attempted and retained for each class. No candidate run was dropped or inferred. Each retained run had one UUID, exactly five markers, and one occurrence of every required event. The automation cleared logcat immediately before the final action, validated the marker set, and waited for the successful terminal UI to auto-dismiss before starting the next run.
