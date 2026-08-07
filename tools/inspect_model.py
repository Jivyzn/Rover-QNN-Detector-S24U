from __future__ import annotations

import sys
from pathlib import Path

import onnx
from onnx import TensorProto

path = Path(sys.argv[1]).expanduser().resolve()
model = onnx.load(str(path), load_external_data=False)
onnx.checker.check_model(model)

print("MODEL PREFLIGHT PASSED")
print(f"Path: {path}")
print(f"IR version: {model.ir_version}")
print(f"Opsets: {[(x.domain or 'ai.onnx', x.version) for x in model.opset_import]}")

print("Inputs:")
for value in model.graph.input:
    tensor = value.type.tensor_type
    shape = [d.dim_value if d.dim_value else d.dim_param for d in tensor.shape.dim]
    print(f"  {value.name}: {TensorProto.DataType.Name(tensor.elem_type)} {shape}")

print("Outputs:")
output_shapes: list[list[object]] = []
for value in model.graph.output:
    tensor = value.type.tensor_type
    shape = [d.dim_value if d.dim_value else d.dim_param for d in tensor.shape.dim]
    output_shapes.append(shape)
    print(f"  {value.name}: {TensorProto.DataType.Name(tensor.elem_type)} {shape}")

ops = {}
for node in model.graph.node:
    ops[node.op_type] = ops.get(node.op_type, 0) + 1
print(f"Nodes: {len(model.graph.node)}")
print(f"EPContext nodes: {ops.get('EPContext', 0)}")
print(f"Top operations: {sorted(ops.items(), key=lambda kv: (-kv[1], kv[0]))[:20]}")

metadata = {entry.key: entry.value for entry in model.metadata_props}
if metadata:
    print("Metadata keys:", sorted(metadata))

if not output_shapes or not any(6 in [x for x in shape if isinstance(x, int)] for shape in output_shapes):
    print("WARNING: No output dimension of 6 was found. Native decoder expects [N,6] or [6,N].")
