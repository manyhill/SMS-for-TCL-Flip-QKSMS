package com.moez.QKSMS.util


import io.realm.Realm

object RealmProvider {
    fun get(): Realm {
        val config = Realm.getDefaultConfiguration()
            ?: throw IllegalStateException("Realm not initialized")
        return Realm.getInstance(config)
    }
}