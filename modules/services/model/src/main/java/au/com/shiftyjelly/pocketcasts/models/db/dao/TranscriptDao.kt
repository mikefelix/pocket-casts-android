package au.com.shiftyjelly.pocketcasts.models.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import au.com.shiftyjelly.pocketcasts.models.to.Transcript
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertBlocking(transcript: Transcript)

    @Query("SELECT * FROM episode_transcript WHERE episode_uuid IS :episodeUuid")
    abstract fun observeTranscriptForEpisode(episodeUuid: String): Flow<Transcript?>
}
