# Versioned DTO migration system

> **Status: proposal — not implemented.** Nothing described here exists in the
> library today; there is no `migrations<...>` DSL and persisted files carry no
> version field. Kept as a written-up design for a known limitation.

## Overview

This document describes a proposed versioned DTO migration system for OkTopoi that would allow explicit schema evolution with type-safe migration functions between versions.

## Problem Statement

Currently, adding new required fields to serialized DTOs requires either:
- Making the field nullable or providing a default value
- Accepting that old persistence files will be archived if they can't be deserialized

While the current Json configuration (`ignoreUnknownKeys`, `coerceInputValues`, etc.) handles many schema evolution cases gracefully, it cannot handle:
- Adding required fields without defaults
- Complex data transformations between versions
- Explicit version tracking

## Proposed Solution

### 1. Versioned Data Format

Each persisted file includes explicit version metadata:

```json
{
  "_v": 1,
  "data": {
    "id": 1,
    "name": "ACME Corp"
  }
}
```

### 2. Migration Definition DSL

```kotlin
// Define your DTOs with explicit versions
@Serializable
data class PartnerV1(
    val id: Long,
    val name: String
)

@Serializable
data class PartnerV2(
    val id: Long,
    val name: String,
    val zipCode: String
)

@Serializable
data class PartnerV3(
    val id: Long,
    val name: String,
    val zipCode: String,
    val country: String
)

// Define migration chain
val partnerMigrations = migrations<Long, PartnerV3>(currentVersion = 3) {
    version(1, PartnerV1.serializer()) { v1 ->
        // Migrate v1 → v2
        PartnerV2(v1.id, v1.name, zipCode = "")
    }

    version(2, PartnerV2.serializer()) { v2 ->
        // Migrate v2 → v3
        PartnerV3(v2.id, v2.name, v2.zipCode, country = "SI")
    }

    version(3, PartnerV3.serializer(), migrate = null) // Current version
}

// Use with OkTopoi
val partners = scope.eps<Long, PartnerV3>(
    migrations = partnerMigrations
) { PartnerV3(0, "", "", "") }
```

## Architecture

### Core Classes

```kotlin
/**
 * Defines migrations for a data type across versions.
 */
class MigrationChain<KeyType, CurrentType>(
    internal val currentVersion: Int,
    internal val migrations: Map<Int, VersionedSerializer<*>>
)

/**
 * Serializer for a specific version with optional migration to next version.
 */
class VersionedSerializer<T>(
    val version: Int,
    val serializer: KSerializer<T>,
    val migrateToNext: ((T) -> Any)?  // Migrate to version+1
)

/**
 * Wrapper for versioned data stored in JSON.
 */
@Serializable
internal data class VersionedData<T>(
    @SerialName("_v") val version: Int,
    val data: T
)

/**
 * DSL Builder for migration chains.
 */
class MigrationChainBuilder<KeyType, CurrentType> {
    private val migrations = mutableMapOf<Int, VersionedSerializer<*>>()

    fun <T> version(
        version: Int,
        serializer: KSerializer<T>,
        migrate: ((T) -> Any)? = null
    ) {
        migrations[version] = VersionedSerializer(version, serializer, migrate)
    }
}

/**
 * DSL entry point.
 */
fun <KeyType, CurrentType> migrations(
    currentVersion: Int,
    builder: MigrationChainBuilder<KeyType, CurrentType>.() -> Unit
): MigrationChain<KeyType, CurrentType>
```

### Integration with Eps.kt

Modified `fromPersistString` with migration support:

```kotlin
override fun fromPersistString(string: String, fileName: String) {
    val key = oktopoiJson.decodeFromString(
        persisted.keyTypeSerializer,
        fileName
    )

    if (migrationChain == null) {
        // No migrations - use current serializer (existing behavior)
        val value = oktopoiJson.decodeFromString(
            persisted.valueTypeSerializer,
            string
        )
        putUnsafe(key, value)
    } else {
        // Try to read version metadata
        val versionedValue = try {
            val jsonElement = Json.parseToJsonElement(string)
            val version = jsonElement.jsonObject["_v"]?.jsonPrimitive?.int
                ?: 1  // Default to v1 if no version field

            // Deserialize with appropriate version's serializer
            val versionSerializer = migrationChain.migrations[version]
                ?: throw SerializationException("No serializer for version $version")

            val data = jsonElement.jsonObject["data"]!!
            val oldValue = oktopoiJson.decodeFromJsonElement(
                versionSerializer.serializer,
                data
            )

            // Apply migrations sequentially
            var current: Any = oldValue
            for (v in version until migrationChain.currentVersion) {
                val migration = migrationChain.migrations[v]?.migrateToNext
                    ?: throw IllegalStateException("Missing migration from v$v to v${v+1}")
                current = migration(current)
            }

            @Suppress("UNCHECKED_CAST")
            current as ValueType

        } catch (e: Exception) {
            // Migration failed - archive and skip
            Logger.e("OkTopoi-Migration", e) {
                "Failed to migrate entry $fileName: ${e.message}"
            }
            archiveCorruptedFile(
                filePath = file,
                fileSystem = this@Eps.fileSystem,
                rootDir = rootDir,
                propertyIdentifier = "${callingClassName}.${propertyName}"
            )
            return
        }

        // Write back with current version (auto-migration on load)
        putUnsafe(key, versionedValue)
        // File will be re-persisted with current version on next write
    }
}
```

Modified `persistEntry` to include version:

```kotlin
override fun persistEntry(key: KeyType, value: ValueType?) {
    if (value != null) {
        val versionedData = if (migrationChain != null) {
            VersionedData(
                version = migrationChain.currentVersion,
                data = value
            )
        } else {
            value  // No versioning - backward compatible
        }

        val content = oktopoiJson.encodeToString(
            if (migrationChain != null)
                VersionedData.serializer(persisted.valueTypeSerializer)
            else
                persisted.valueTypeSerializer,
            versionedData
        )
        writeToFile(key, content)
    } else {
        deleteFromFile(key)
    }
}
```

## File Evolution Example

### V1 file (old):
```json
{
  "_v": 1,
  "data": {
    "id": 1,
    "name": "ACME Corp"
  }
}
```

### Loading with V3 code:
1. Deserializes with `PartnerV1.serializer()`
2. Applies migration: V1→V2 (adds `zipCode = ""`)
3. Applies migration: V2→V3 (adds `country = "SI"`)
4. In-memory: `PartnerV3(1, "ACME Corp", "", "SI")`

### Next save writes:
```json
{
  "_v": 3,
  "data": {
    "id": 1,
    "name": "ACME Corp",
    "zipCode": "",
    "country": "SI"
  }
}
```

## Benefits

✅ **Explicit versioning** - Clear version numbers in each file
✅ **Multi-step migrations** - V1→V2→V3 automatically
✅ **Auto-migration on load** - Old files automatically updated on first access
✅ **Type-safe** - Compile-time checking of migration functions
✅ **Testable** - Can test each migration step independently
✅ **Backwards compatible** - Works alongside existing serialization (opt-in)
✅ **Fallback to archiving** - Migration failures archive files gracefully
✅ **Incremental adoption** - Only use for DTOs that need complex migrations

## Tradeoffs

### Pros:
- Clean, explicit versioning
- Type-safe migrations
- Automatic migration on first load
- Works well with existing OkTopoi architecture
- Testable migration logic

### Cons:
- Slightly larger file size (version metadata ~10 bytes per file)
- Need to keep old DTO definitions in codebase
- Can't delete intermediate version definitions if users still have V1 files
- Added complexity for simple cases that current defaults handle

## When to Use

**Use migrations when:**
- Adding required fields without reasonable defaults
- Complex data transformations (e.g., splitting/combining fields)
- Renaming fields (can also use `@SerialName`)
- Changing data types in non-trivial ways
- Need audit trail of schema changes

**Don't use migrations when:**
- Adding nullable fields (just add `val field: String? = null`)
- Adding fields with sensible defaults (just add `val field: String = ""`)
- Removing fields (current `ignoreUnknownKeys = true` handles this)
- Changing defaults (current `encodeDefaults = false` handles this)

## Alternative Approaches

### 1. Metadata File Approach

Store version in separate file instead of each entry:

```
Data.Partners/
  ├── .version  (contains: version=3)
  ├── 1.json
  ├── 2.json
  └── ...
```

**Pros:** Smaller individual files
**Cons:** Harder to track per-entry versions, all entries must be same version

### 2. Filename-Based Versioning

Encode version in directory structure:

```
Data.Partners/
  ├── v1/
  │   ├── 1.json
  │   └── 2.json
  └── v3/
      ├── 1.json
      └── 3.json
```

**Pros:** Version clear from filesystem
**Cons:** Complex directory management, hard to atomically migrate

### 3. @SerialName Annotations (Current Approach)

Use kotlinx.serialization features for simple renames:

```kotlin
@Serializable
data class Partner(
    val id: Long,
    @SerialName("name")
    @SerialName("full_name")  // Also accept old name
    val fullName: String
)
```

**Pros:** No migration code needed
**Cons:** Limited to field renames, gets verbose

## Implementation Plan

If implementing this system:

### Phase 1: Core Migration Support
1. Implement `MigrationChain`, `VersionedSerializer`, `VersionedData` classes
2. Add migration support to `Eps` class
3. Add factory functions to accept optional `migrations` parameter
4. Write unit tests for migration logic

### Phase 2: Extended Support
1. Add migration support to `Ep` (single values)
2. Add migration support to `Esps` (with sync metadata)
3. Add migration support to `Esp` (single values with sync)

### Phase 3: Developer Experience
1. Create testing utilities for migration verification
2. Add documentation and examples
3. Create diagnostic tools (show version distribution, validate migration chains)

### Phase 4: Advanced Features
1. Support for conditional migrations (e.g., only if field matches condition)
2. Support for batch migration (migrate all files at once on app start)
3. Support for rollback (downgrade to previous version)

## Open Questions

1. **Version number storage**: In each file vs separate metadata file?
2. **Backward compatibility**: How to handle files with no version field? (Proposed: default to v1)
3. **Version numbers**: Sequential integers vs semantic versioning?
4. **Migration timing**: On load (current proposal) vs on background task?
5. **Old version cleanup**: When can we safely remove old DTO definitions and migrations?
6. **Performance**: Impact of migration on startup time for large collections?

## Related Work

- Kotlin Multiplatform Schema Evolution: https://kotlinlang.org/docs/serialization.html#schema-evolution
- Room Database Migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions
- Protocol Buffers Schema Evolution: https://protobuf.dev/programming-guides/proto3/#updating

## Status

Proposal, written 2025-12-04. Unimplemented; revisit when a required field
without a default actually needs to be added to a persisted DTO.
