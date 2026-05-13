@file:Repository("https://repo1.maven.org/maven2/")
import kotlin.system.measureTimeMillis

class DummyDoc(val isDirectory: Boolean, val name: String, val children: List<DummyDoc> = emptyList())

fun buildTree(depth: Int, width: Int): DummyDoc {
    if (depth == 0) return DummyDoc(false, "file.txt")
    val children = List(width) { i -> buildTree(depth - 1, width) }
    return DummyDoc(true, "dir", children)
}

val root = buildTree(10, 3) // ~88k nodes

// Warmup
fun traverseOld(doc: DummyDoc, currentPath: String) {
    if (doc.isDirectory) {
        doc.children.forEach { child ->
            val nextPath = if (currentPath.isEmpty()) child.name else "$currentPath/${child.name}"
            traverseOld(child, nextPath)
        }
    }
}

fun traverseNew(doc: DummyDoc, currentPath: StringBuilder) {
    if (doc.isDirectory) {
        doc.children.forEach { child ->
            val len = currentPath.length
            if (len > 0) currentPath.append("/")
            currentPath.append(child.name)
            traverseNew(child, currentPath)
            currentPath.setLength(len)
        }
    }
}

for (i in 1..5) traverseOld(root, "")
for (i in 1..5) traverseNew(root, StringBuilder())

val timeOld = measureTimeMillis {
    for (i in 1..50) traverseOld(root, "")
}

val timeNew = measureTimeMillis {
    for (i in 1..50) traverseNew(root, StringBuilder())
}

println("Old path construction: $timeOld ms")
println("New path construction: $timeNew ms")
