"""Patch UniFFI's generated Kotlin: SyncError::Storage has a field named
`message`, which collides with Throwable.message. Rename it to `detail`."""
import re, sys

path = sys.argv[1]
src = open(path).read()
start = src.index("class Storage(")
end = src.index("\n    }\n", start)
block = src[start:end]
block = block.replace("val `message`: kotlin.String", "val `detail`: kotlin.String")
block = block.replace("message=${ `message` }", "detail=${ `detail` }")
src = src[:start] + block + src[end:]
# Converter read/write/allocation for Storage use value.`message`.
src = src.replace("value.`message`", "value.`detail`")
open(path, "w").write(src)
print("patched", path)
