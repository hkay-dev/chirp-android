import java.io.File

fun main() {
    val dir = File("/tmp/testdir")
    dir.mkdirs()
    File(dir, "f").createNewFile()
    var success = true
    success = dir.deleteRecursively() && success
    println(success)
}
