package invoicing.domain

import java.util.UUID

import com.fasterxml.uuid.Generators
import com.fasterxml.uuid.NoArgGenerator

/**
  * Single source of UUIDs for the application.
  *
  * Generates UUIDv7 (Unix-epoch time-ordered) rather than the v4 that `UUID.randomUUID` returns.
  * Every identifier minted here becomes a PostgreSQL primary key, and time-ordered values keep
  * B-tree inserts at the right-hand edge of the index instead of scattering them across the whole
  * keyspace, which is what v4 does.
  *
  * Note that a v7 value embeds its creation time in the high bits, so these ids reveal roughly when
  * the row was created to anyone who sees one.
  *
  * JUG's generators are thread-safe, so one shared instance serves the whole process.
  */
object UuidGen {

  private val generator: NoArgGenerator = Generators.timeBasedEpochGenerator()

  def next(): UUID = generator.generate()

}
