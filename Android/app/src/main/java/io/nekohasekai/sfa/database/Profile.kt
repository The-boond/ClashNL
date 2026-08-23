package io.nekohasekai.sfa.database

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.parcelize.Parcelize

@Entity(
    tableName = "profiles",
)
@TypeConverters(TypedProfile.Convertor::class)
@Parcelize
class Profile(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var name: String = "",
    @ColumnInfo(defaultValue = "NULL") var icon: String? = null,
    var typed: TypedProfile = TypedProfile(),
) : Parcelable {
    @androidx.room.Dao
    @TypeConverters(TypedProfile.Convertor::class)
    interface Dao {
        @Insert
        suspend fun insert(profile: Profile): Long

        @Update
        suspend fun update(profile: Profile): Int

        @Update
        suspend fun update(profile: List<Profile>): Int

        @Query("UPDATE profiles SET typed = :typed WHERE id = :profileId")
        suspend fun updateTyped(profileId: Long, typed: TypedProfile): Int

        @Query("UPDATE profiles SET name = :name, icon = :icon, typed = :typed WHERE id = :profileId")
        suspend fun updateEditable(profileId: Long, name: String, icon: String?, typed: TypedProfile): Int

        @Delete
        suspend fun delete(profile: Profile): Int

        @Delete
        suspend fun delete(profile: List<Profile>): Int

        @Query("SELECT * FROM profiles WHERE id = :profileId")
        suspend fun get(profileId: Long): Profile?

        @Query("select * from profiles order by userOrder asc")
        suspend fun list(): List<Profile>

        @Query("DELETE FROM profiles")
        suspend fun clear()

        @Query("SELECT MAX(userOrder) + 1 FROM profiles")
        suspend fun nextOrder(): Long?

        @Query("SELECT MAX(id) + 1 FROM profiles")
        suspend fun nextFileID(): Long?
    }
}
