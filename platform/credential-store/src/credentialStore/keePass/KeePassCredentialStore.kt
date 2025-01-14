// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.credentialStore.keePass

import com.intellij.credentialStore.*
import com.intellij.credentialStore.kdbx.IncorrectMainPasswordException
import com.intellij.credentialStore.kdbx.KdbxPassword
import com.intellij.credentialStore.kdbx.KeePassDatabase
import com.intellij.credentialStore.kdbx.loadKdbx
import com.intellij.ide.passwordSafe.PasswordStorage
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.delete
import com.intellij.util.io.safeOutputStream
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists

const val DB_FILE_NAME: String = "c.kdbx"

fun getDefaultKeePassBaseDirectory(): Path = PathManager.getConfigDir()

fun getDefaultMainPasswordFile(): Path = getDefaultKeePassBaseDirectory().resolve(MAIN_KEY_FILE_NAME)

/**
 * preloadedMainKey [MainKey.value] will be cleared
 */
internal class KeePassCredentialStore(internal val dbFile: Path, private val mainKeyStorage: MainKeyFileStorage, preloadedDb: KeePassDatabase? = null) : BaseKeePassCredentialStore() {
  constructor(dbFile: Path, mainKeyFile: Path) : this(dbFile = dbFile,
                                                      mainKeyStorage = MainKeyFileStorage(mainKeyFile),
                                                      preloadedDb = null)

  private val isNeedToSave: AtomicBoolean

  override var db: KeePassDatabase = if (preloadedDb == null) {
    isNeedToSave = AtomicBoolean(false)
    if (dbFile.exists()) {
      val mainPassword = mainKeyStorage.load() ?: throw IncorrectMainPasswordException(isFileMissed = true)
      loadKdbx(dbFile, KdbxPassword.createAndClear(mainPassword))
    }
    else {
      KeePassDatabase()
    }
  }
  else {
    isNeedToSave = AtomicBoolean(true)
    preloadedDb
  }

  val mainKeyFile: Path
    get() = mainKeyStorage.passwordFile

  @Synchronized
  @TestOnly
  fun reload() {
    val key = mainKeyStorage.load()!!
    val kdbxPassword = KdbxPassword(key)
    key.fill(0)
    db = loadKdbx(dbFile, kdbxPassword)
    isNeedToSave.set(false)
  }

  @Synchronized
  fun save(mainKeyEncryptionSpec: EncryptionSpec) {
    if (!isNeedToSave.compareAndSet(true, false) && !db.isDirty) {
      return
    }

    try {
      val secureRandom = createSecureRandom()
      val mainKey = mainKeyStorage.load()
      val kdbxPassword: KdbxPassword
      if (mainKey == null) {
        val key = generateRandomMainKey(mainKeyEncryptionSpec, secureRandom)
        kdbxPassword = KdbxPassword(key.value!!)
        mainKeyStorage.save(key)
      }
      else {
        kdbxPassword = KdbxPassword(mainKey)
        mainKey.fill(0)
      }

      dbFile.safeOutputStream().use {
        db.save(kdbxPassword, it, secureRandom)
      }
    }
    catch (e: Throwable) {
      // schedule a save again
      isNeedToSave.set(true)
      logger<KeePassCredentialStore>().error("Cannot save password database", e)
    }
  }

  @Synchronized
  fun isNeedToSave() = isNeedToSave.get() || db.isDirty

  @Synchronized
  fun deleteFileStorage() {
    try {
      dbFile.delete()
    }
    finally {
      mainKeyStorage.save(null)
    }
  }

  fun clear() {
    db.rootGroup.removeGroup(ROOT_GROUP_NAME)
    isNeedToSave.set(db.isDirty)
  }

  @TestOnly
  fun setMainPassword(mainKey: MainKey, secureRandom: SecureRandom) {
    // KdbxPassword hashes value, so, it can be cleared before a file write (to reduce time when main password exposed in memory)
    saveDatabase(dbFile = dbFile, db = db, mainKey = mainKey, mainKeyStorage = mainKeyStorage, secureRandom = secureRandom)
  }

  override fun markDirty() {
    isNeedToSave.set(true)
  }
}

class InMemoryCredentialStore : BaseKeePassCredentialStore(), PasswordStorage {
  override val db = KeePassDatabase()

  override fun markDirty() {
  }
}

internal fun generateRandomMainKey(mainKeyEncryptionSpec: EncryptionSpec, secureRandom: SecureRandom): MainKey {
  val bytes = secureRandom.generateBytes(512)
  return MainKey(Base64.getEncoder().withoutPadding().encode(bytes), isAutoGenerated = true, encryptionSpec = mainKeyEncryptionSpec)
}

internal fun saveDatabase(dbFile: Path, db: KeePassDatabase, mainKey: MainKey, mainKeyStorage: MainKeyFileStorage, secureRandom: SecureRandom) {
  val kdbxPassword = KdbxPassword(mainKey.value!!)
  mainKeyStorage.save(mainKey)
  dbFile.safeOutputStream().use {
    db.save(kdbxPassword, it, secureRandom)
  }
}

internal fun copyTo(from: Map<CredentialAttributes, Credentials>, store: CredentialStore) {
  for ((k, v) in from) {
    store.set(k, v)
  }
}
