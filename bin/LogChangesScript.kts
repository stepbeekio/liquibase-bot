#!/usr/bin/env kscript

@file:DependsOn("org.liquibase:liquibase-core:4.26.0")
@file:DependsOn("com.github.ajalt.clikt:clikt:4.2.2")

import liquibase.change.core.AddNotNullConstraintChange
import liquibase.change.core.CreateTableChange
import liquibase.change.core.DropColumnChange
import liquibase.change.core.DropTableChange
import liquibase.changelog.ChangeLogParameters
import liquibase.database.core.PostgresDatabase
import liquibase.parser.core.xml.XMLChangeLogSAXParser
import liquibase.resource.DirectoryResourceAccessor
import java.io.File
import java.nio.file.Path
//import com.github.ajalt.clikt.core.CliktCommand

sealed interface BreakingDatabaseChange {
    val tableName: String
    val fileName: String

    fun regex(): Regex

    fun lineNumber(file: File): Int {
        val lines = file.readLines()
        val regex = regex()
        var lineNumber = 0

        val entireFile = lines.joinToString("")
        val groups = regex.findAll(entireFile).find { result ->
            result.groupValues.any { matchAttributes(it) }
        }

        if (groups != null) {
            val startIndex = groups.range.first

            var cumulative = 0
            lineNumber = lines.mapIndexed { idx, line ->
                cumulative += line.length
                (cumulative - line.length)..cumulative to idx
            }.find { it.first.contains(startIndex) }
                ?.second ?: lineNumber
        }

        return lineNumber + 1

    }

    fun matchAttributes(line: String): Boolean

    fun isBreakingChange(others: List<BreakingDatabaseChange>): Boolean = true

    fun message(): String
}

data class TableWasCreated(override val tableName: String, override val fileName: String) : BreakingDatabaseChange {
    override fun message(): String =
        "Not a breaking change"

    override fun regex(): Regex {
        return Regex("<createTable\\s+([^>]+)>")
    }

    override fun matchAttributes(line: String): Boolean =
        line.contains("tableName=\"$tableName\"")

    override fun isBreakingChange(others: List<BreakingDatabaseChange>): Boolean = false
}

data class TableWasDropped(override val tableName: String, override val fileName: String) : BreakingDatabaseChange {
    override fun regex(): Regex {
        return Regex("<dropTable\\s+([^>]+)>")
    }

    override fun matchAttributes(line: String): Boolean = line.contains("tableName=\"$tableName\"")

    override fun message(): String =
        "Dropping the table $tableName may cause running instances of the service to fail during the deployment. It will also make a rollback of this change non-trivial."
}

data class ColumnWasDropped(override val tableName: String, val columnName: String, override val fileName: String) :
    BreakingDatabaseChange {
    override fun regex(): Regex {
        return Regex("<dropColumn\\s+([^>]+)>")
    }

    override fun matchAttributes(line: String): Boolean = line.contains("tableName=\"$tableName\"")
            && line.contains("columnName=\"$columnName\"")

    override fun message(): String =
        "Dropping the column $tableName.$columnName may cause running instances of the service to fail during the deployment. It will also make a rollback of this change non-trivial."
}

data class NotNullConstraintWasAdded(
    override val tableName: String, val columnName: String,
    override val fileName: String
) : BreakingDatabaseChange {
    override fun regex(): Regex {
        return Regex("<addNotNullConstraint\\s+([^>]+)>")
    }

    override fun matchAttributes(line: String): Boolean = line.contains("tableName=\"$tableName\"")
            && line.contains("columnName=\"$columnName\"")

    override fun isBreakingChange(others: List<BreakingDatabaseChange>): Boolean {
        return others.find { it.tableName == tableName && it is TableWasCreated } == null
    }

    override fun message(): String =
        "Adding a not-null constraint on column $columnName for table $tableName which already exists could break existing instances of the service while deploying and makes rolling back non-trivial."
}

fun main(files: List<String>) {
    val database = PostgresDatabase()
    val parameters = ChangeLogParameters(database)
    val results = files.flatMap {
        XMLChangeLogSAXParser().parse(
            it, parameters, DirectoryResourceAccessor(
                Path.of(".")
            )
        ).changeSets
    }

    val mapped = results.flatMap {
        it.changes
    }.mapNotNull {
        when (it) {
            is AddNotNullConstraintChange -> NotNullConstraintWasAdded(
                it.tableName,
                it.columnName,
                it.changeSet.filePath
            )

            is CreateTableChange -> TableWasCreated(it.tableName, it.changeSet.filePath)
            is DropColumnChange -> ColumnWasDropped(it.tableName, it.columnName, it.changeSet.filePath)
            is DropTableChange -> TableWasDropped(it.tableName, it.changeSet.filePath)
            else -> null
        }
    }


    val filtered = mapped.filter { it.isBreakingChange(mapped) }

    filtered.forEach {
        println(
            """
                ------
                Breaking change in file ${it.fileName} on line ${it.lineNumber(File(it.fileName))}.
                ${it.message()}
                ------
            """.trimIndent()
        )
    }
}

//class CheckForBreakingChanges : CliktCommand() {
//    val files: List<String> by option().prompt("files").help("The changeset files to parse")
//
//    override fun run() {
//        main(files)
//    }
//}

// Working
//main(listOf("examples/example-changeset.xml"))

// Don't know about...
main(listOf("examples/example-changeset.xml", "examples/example2-changeset.xml"))
