# MXINT8 in-sim validation — what the "hang" actually was (2026-06-29)

## TL;DR (supersedes all earlier versions of this file)
There was **never a real hang**, and the gemmini/MX **hardware is not broken**. Every
"mx_bench hangs / latent X-prop hang / mx_bench-binary-specific hang" conclusion in prior
notes is **WRONG and retracted**. What actually happened:

1. **Three stacked measurement artifacts** made healthy-but-slow runs look hung.
2. The only real effect is that **boot time scales with the binary's memory footprint**
   because the default **TSI serial loader writes the whole image (including `.bss`)** before
   releasing the core, and Verilator is slow. mx_bench's **423 KB `.bss`** ⇒ very slow boot.
3. **Fixes:** run with **`+loadmem`** (preloads DRAM, skips the serial transfer — boots any
   size fast) **or** shrink the `.bss`.

The perf headline (repack = 84 % of MX 256³; MX-minus-repack ≈ 29.5k < stock 35.2k; fix in
30edd64) is unaffected — it came from completed runs.

## The three artifacts that faked a "hang"
1. **`+verbose` commit-log is stdio-buffered**, flushed only at `$finish`. A run **killed by a
   timeout** therefore shows only the first ~54 cycles on disk → looks "stuck in BootROM `wfi`
   at pc=0x10034". Not real: bound `+max-cycles` small so the **watchdog `$finish` fires and
   flushes**, then read the true last PC.
2. **Bare-metal `printf` is fully buffered**, flushed only at `exit()`/`fflush`. A killed run
   shows **no program output even if `main()` ran**. Fix: `setvbuf(stdout, NULL, _IONBF, 0)`
   at the top of `main`, or `fflush` after key prints, or just let the run reach `$finish`.
3. **Wall-clock timeouts were far too short.** Verilator runs ~a few k cycles/s; hello.riscv
   alone is 483k cycles. 90–115 s caps killed legitimately-slow runs mid-flight. Plus a
   **stray 3 h verbose sim** (a self-killing `pkill -f` had matched its own shell and never
   killed the target) was pinning a core plus skewing everything.

## Evidence chain (all reproducible, GemminiMXINT8DIM32 fast-init sim)
- `hello.riscv` (libgloss htif_nano) **boots & PASSes** — woke from BootROM `wfi` at cycle
  168, ran to `$finish`.
- A copy of `hello.c` **+ a 42 KB `.bss` array** (memsz ~50 KB), otherwise trivial, **does NOT
  boot** within a short cap, while plain hello (memsz 8.7 KB) does → **footprint, not content,
  gates boot**. Same `.bss` array **+ `+loadmem` boots instantly** (`DONE`, `$finish`).
- **`mxint8_corner-baremetal` (12.5 KB `.bss`) PASSES via the standard `run-binary-fast` flow**
  (TSI, no loadmem, 300M-cycle budget) → the regression flow works; the small tests are fine.
- **64³ MX matmul completed** (`TRACE,post_run_gemm` printed, then `MXBENCH`) → **gemmini/MX
  datapath does not hang.** (That htif test build showed `cycles=lu` garbage only because the
  libgloss *nano* printf lacks `%llu`; the riscv-tests build prints real numbers.)

## Why mx_bench's `.bss` is 423 KB (and how to keep it small)
Full 256×256 static matrices for the largest shape (`bareMetalC/mx_bench.c`):

| array | type | bytes |
|---|---|---|
| `c_hw[256][256]` | `acc_t` = **int32** | **256 KB** ← dominant |
| `a_payload[256][256]` | int8 | 64 KB |
| `b_payload[256][256]` | int8 | 64 KB |
| scales + `gold_row` | — | ~5 KB |

`c_hw` (int32 output) dominates. To keep the footprint bootable on the plain TSI flow: cap the
shape table — **128³ → `c_hw` 64 KB** (total ~100 KB), **64³ → 16 KB** (total ~25 KB). The
repack-fix benefit is still visible below 256³ (repack was 78 % of runtime at 128³, 42 % at
64³), and the 256³ headline is already measured. Alternatively keep 256³ and always run it
with `+loadmem`.

## Correct methodology (do this, not the old way)
- **Boot/footprint:** large binaries → run the sim with **`+loadmem=<elf>`** in addition to the
  ELF arg (preloads DRAM; boots any size fast). Plain TSI is fine for ≲tens-of-KB binaries.
- **Don't trust a killed run's output** — neither the verbose log nor `printf`. Either let it
  reach `$finish`, or bound `+max-cycles` so the **watchdog `$finish` flushes**, or build the
  test with **unbuffered stdout**.
- **Verbose commit trace:** `+verbose` between `+permissive`/`+permissive-off`; the commit log
  is `C0:  <cycle> [1] pc=[...] ... inst=[...]`. pc=0x10000–0x10034 = BootROM; first
  pc=0x80000000 = program entry. BootROM parks in `wfi` until fesvr/TSI rings MSIP post-load
  (see `testchipip/.../bootrom/bootrom.S`).
- **Kill sims by exact name/PID**, never `pkill -f <pattern>` where the pattern can match the
  command's own shell (that no-ops the kill and can SIGTERM the wrapper).
- **Boot sanity:** `tests/build/hello.riscv` (CMake/libgloss) should `$finish`; if it does, the
  sim is good.

## Build-flow reference (tests/ vs gemmini-rocc-tests)
Same compiler (riscv64-unknown-elf-gcc 13.2.0, conda). Differences:

| | `tests/` (CMake) → `*.riscv` | gemmini `Makefrag` → `*-baremetal` |
|---|---|---|
| runtime | `-specs=htif_nano.specs` (libgloss crt0 + libc + HTIF) | `-nostdlib -nostartfiles` + riscv-tests `common/{crt.S,syscalls.c}` |
| linker script | `-T htif.ld` | `-T riscv-tests/.../common/test.ld` |
| `-march` | `rv64imafd` | `rv64gc` |
| printf | full libc (`%llu` ok) | riscv-tests printf (`%llu` ok); **libgloss *nano* lacks `%llu`** |
| segments | 1 PT_LOAD | 2 PT_LOAD (`.text.init`@0x80000000 + `.tohost`/data@0x80001000) |

The build flow is **not** the boot blocker — a gemmini-style compile of `hello.c` boots fine,
and compiling mx_bench the tests/ way did not change the boot behavior. Footprint does.

## Repack-cache fix — VALIDATED in-sim (2026-06-29, GemminiMXINT8DIM32, `+loadmem`)
Real `mx_bench` (riscv-tests build, `%llu`), run to clean `$finish` (`mx_bench: PASS`). All
shapes **bit-exact PASS**. The per-(j,k) tile cache (commit 30edd64) does exactly what it should:

| shape | total cyc | repack_cyc | issue_cyc | result | pre-fix total / repack |
|-------|-----------|------------|-----------|--------|------------------------|
| 64³   | 5,397     | 1,492      | 70        | PASS   | 5,952 / 2,513          |
| 128³  | 15,402    | 5,028      | 2,385     | PASS   | 26,688 / 20,947        |
| 256³  | **74,484**| **22,452** | 16,197    | PASS   | 181,703 / 152,234      |

- **256³: repack 152,234 → 22,452 (6.8×); total 181,703 → 74,484 (2.4× faster), bit-exact.**
- Remaining gap to stock (35,238 @ 256³) is still host-side: `no_cmd=61,503` (mesh starved 82%)
  = residual one-time repack (22.5k) + command issue (16.2k) not feeding the mesh. The 22.5k
  repack is a **one-time per-tile** cost still inside the timed region → **Phase C** (pre-tile
  the constant weight B-scales offline) removes it.

## Phase C — offline B-scale pre-tile VALIDATED (2026-06-30)
The constant weight B-scales are now pre-tiled **once, untimed**, removing the per-chunk repack
from the timed GEMM entirely. API in `gemmini.h`: `mxint8_pretile_b_scales` (fills a caller
buffer of `mx_JC*mx_KC` `MX_PRETILE_SLOT_ELEMS`-sized slots) + `tiled_matmul_mxint8_pretiled`
(consumes it; `g_mx_btile_valid` forced true → no repack). `tiled_matmul_mxint8` is unchanged
(forwards with NULL) so the bit-exact test callers are byte-identical. `mx_bench` uses the
pretiled entry; pre-tile happens after `gen_inputs`, before `read_cycles()`.

Measured (GemminiMXINT8DIM32, `+loadmem`, `mx_bench: PASS`, all shapes bit-exact):

| shape | total cyc | repack_cyc | issue_cyc | result | cache-fix total | stock |
|-------|-----------|------------|-----------|--------|-----------------|-------|
| 64³   | 3,345     | **0**      | 69        | PASS   | 5,397           | —     |
| 128³  | 10,102    | **0**      | 2,457     | PASS   | 15,402          | —     |
| 256³  | **57,423**| **0**      | 21,258    | PASS   | 74,484          | 35,238|

- repack fully eliminated; 256³ 74,484 → 57,423 (3.16× vs original 181,703). **Still 1.63×
  stock** — residual is host command-issue (`issue_cyc=21,258`) + mesh feed (`no_cmd=59,287`,
  mesh now 49% busy). Closing the rest needs fewer/cheaper commands or better feed overlap
  (DO-NOT-TOUCH boundary). Open decision, not pursued yet.

## Bit-exact gate GREEN across all DIMs (2026-06-30)
`run_regression.sh --dims=32,16,8,4`: **DIM32 8/8, DIM16 3/3, DIM8 2/2, DIM4 2/2, stock
elaborate — OVERALL PASS.** Phase C is bit-exact on the DIM<32 two-phase `mx_raw_buf` path too.

**Toolchain trap found & fixed:** the first DIM16/8/4 pass FAILED with `firtool: unexpected
character`. Cause: those sims were stale (older than the 06-27 gemmini bump), so
`run-binary-fast` silently **re-elaborated** them — but `run_one()` did not pass `FIRTOOL_BIN`,
so the rebuild used the system `/usr/local/bin/firtool` (**LLVM 17**) instead of the pinned
1.62.1 (**LLVM 18**), which cannot parse the .fir dialect. DIM32 never rebuilt (its sim was
current) so it was unaffected. Fix: `run_one` now passes `FIRTOOL_BIN="$FIRTOOL"`. Lesson: a
sim "FAIL" that is actually a firtool parse error during an unintended rebuild ≠ a correctness
failure — always check whether the sim re-elaborated.

## Next
- Decide: bank Phase C (1.63× stock, bit-exact, deployment-realistic) vs. push host
  command-issue / mesh-feed overlap toward stock parity (riskier; DO-NOT-TOUCH-adjacent).
- Refresh the full stock-vs-MX matrix via `run_perf.sh` (the old DIM32 table is repack-dominated).
