package chipyard

import org.chipsalliance.cde.config.Config

// ------------------------------
// Configs with Gemmini RoCC
// ------------------------------

// DOC include start: GemminiRocketConfig
class GemminiRocketConfig extends Config(
  new gemmini.DefaultGemminiConfig ++                            // use Gemmini systolic array GEMM accelerator
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)
// DOC include end: GemminiRocketConfig

class FPGemminiRocketConfig extends Config(
  new gemmini.GemminiFP32DefaultConfig ++                         // use FP32Gemmini systolic array GEMM accelerator
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class LeanGemminiRocketConfig extends Config(
  new gemmini.LeanGemminiConfig ++                                 // use Lean Gemmini systolic array GEMM accelerator
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class LeanGemminiPrintfRocketConfig extends Config(
  new gemmini.LeanGemminiPrintfConfig ++                                 // use Lean Gemmini systolic array GEMM accelerator
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class ReRoCCManyGemminiConfig extends Config(
  new rerocc.WithReRoCC ++
  new gemmini.LeanGemminiConfig ++                              // rerocc tile3 is gemmini
  new gemmini.LeanGemminiConfig ++                              // rerocc tile2 is gemmini
  new gemmini.LeanGemminiConfig ++                              // rerocc tile1 is gemmini
  new gemmini.LeanGemminiConfig ++                              // rerocc tile0 is gemmini
  new freechips.rocketchip.rocket.WithNHugeCores(4) ++           // 4 rocket cores
  new chipyard.config.AbstractConfig)

class GemminiShuttleConfig extends Config(
  new gemmini.DefaultGemminiConfig ++                            // use Gemmini systolic array GEMM accel
  new shuttle.common.WithNShuttleCores ++
  new chipyard.config.AbstractConfig)

// ------------------------------
// MXINT8 (OCP microscaling int8) Gemmini configs
// ------------------------------

class GemminiMXINT8DIM32RocketConfig extends Config(
  new gemmini.GemminiMXINT8DIM32Config ++                        // 32x32 MXINT8 block-scaled Gemmini
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class GemminiMXINT8DIM16RocketConfig extends Config(
  new gemmini.GemminiMXINT8DIM16Config ++                        // 16x16 MXINT8 two-phase Gemmini
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class GemminiMXINT8DIM8RocketConfig extends Config(
  new gemmini.DefaultGemminiConfig(gemmini.GemminiConfigs.mxint8DIM8Config) ++   // 8x8 MXINT8 (4-phase)
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class GemminiMXINT8DIM4RocketConfig extends Config(
  new gemmini.DefaultGemminiConfig(gemmini.GemminiConfigs.mxint8DIM4Config) ++   // 4x4 MXINT8 (8-phase)
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

// ------------------------------
// Stock (non-MX) twins for fair stock-vs-MX comparison: byte-identical mesh/spad/acc to
// the MX config at each DIM, mx_enabled=false. (GemminiRocketConfig above uses the base
// defaultConfig and is NOT a fair DIM=16 twin — chipConfig has different capacities.)
// ------------------------------

class GemminiStockDIM32RocketConfig extends Config(
  new gemmini.DefaultGemminiConfig(gemmini.GemminiConfigs.stockDIM32Config) ++  // 32x32 stock twin
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class GemminiStockDIM16RocketConfig extends Config(
  new gemmini.DefaultGemminiConfig(gemmini.GemminiConfigs.stockDIM16Config) ++  // 16x16 stock twin
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class GemminiStockDIM8RocketConfig extends Config(
  new gemmini.DefaultGemminiConfig(gemmini.GemminiConfigs.stockDIM8Config) ++   // 8x8 stock twin
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)

class GemminiStockDIM4RocketConfig extends Config(
  new gemmini.DefaultGemminiConfig(gemmini.GemminiConfigs.stockDIM4Config) ++   // 4x4 stock twin
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.WithSystemBusWidth(128) ++
  new chipyard.config.AbstractConfig)
