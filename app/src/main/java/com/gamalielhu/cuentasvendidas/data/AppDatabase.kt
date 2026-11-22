package com.gamalielhu.cuentasvendidas.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM cuentas")
    fun observar(): Flow<List<AccountRecord>>

    @Query("SELECT * FROM cuentas")
    suspend fun todas(): List<AccountRecord>

    /** Devuelve el id nuevo al insertar, o -1 si actualizó. */
    @Upsert
    suspend fun guardar(r: AccountRecord): Long

    @Delete
    suspend fun borrar(r: AccountRecord)
}

@Database(entities = [AccountRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AccountDao

    companion object {
        @Volatile private var instancia: AppDatabase? = null

        fun get(ctx: Context): AppDatabase = instancia ?: synchronized(this) {
            instancia ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "cuentas.db")
                .build().also { instancia = it }
        }
    }
}
