# ECHO Mind release shrinking rules.
# Keep Room generated database implementation and FastAPI JSON field names used by the hand-written client.
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn org.json.**
