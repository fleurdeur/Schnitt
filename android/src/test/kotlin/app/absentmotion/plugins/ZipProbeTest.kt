package app.absentmotion.plugins
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.junit.Test
import java.io.File
class ZipProbeTest {
 @Test fun noPassword() {
  val f=File("C:/Users/Rogie/Downloads/GridTrend v1.0.0 - Keyflame.7z")
  runCatching { SevenZFile.builder().setFile(f).get().use { it.entries.toList() } }.onFailure { println("NOPASS FAIL "+it) }
 }
 @Test fun password() {
  val f=File("C:/Users/Rogie/Downloads/GridTrend v1.0.0 - Keyflame.7z")
  SevenZFile.builder().setFile(f).setPassword("flame").get().use { println("PASS "+it.entries.map{e->e.name}) }
 }
}
