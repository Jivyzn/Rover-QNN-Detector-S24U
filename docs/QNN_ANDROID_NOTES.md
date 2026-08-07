# QNN Android notes

This file is here because the Android QNN issue took way longer to isolate than the actual detector code.

## The failure

The standalone Qualcomm QNN Plugin EP 2.4.0 could register successfully but Android returned no QNN `OrtEpDevice`.

The app would basically reach:

```text
QNN plugin registered
Devices=[CPUExecutionProvider]
```

and stop before a QNN session could even be created.

## Root cause

The 2.4.0 provider tried to detect the Qualcomm NPU by enumerating:

```text
/dev/fastrpc-cdsp*
```

That works on Linux ARM64 but normal Android app UIDs cannot enumerate `/dev` because of SELinux.

The funny part is QAIRT itself can still reach CDSP through the Android HAL, so the NPU works fine. The discovery check was what was broken.

## Current bridge in this repo

Before ORT is created:

```java
Os.setenv("ORT_QNN_ENABLE_CPU_BACKEND", "1", true);
```

This makes the plugin expose a selectable QNN device. The session then explicitly uses:

```java
providerOptions.put("backend_type", "htp");
```

so the selected QNN backend is Hexagon HTP.

I am not using the QNN CPU backend for inference.

## Upstream fix

Qualcomm fixed the Android discovery logic upstream on **7 August 2026** in `onnxruntime/onnxruntime-qnn` PR #683 / commit:

```text
d4547ae349c2c883f87b2edba95d84ffe19af173
```

Instead of scanning `/dev` on Android, the fixed code checks the Android SoC manufacturer property (`QTI`) and synthesises the NPU hardware device correctly.

Once that fix is in a published Android Maven package, this project's discovery bridge can be removed.
