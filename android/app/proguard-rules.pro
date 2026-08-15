# ECHO Mind release shrinking rules.
# Keep Room generated database implementation and FastAPI JSON field names used by the hand-written client.
-keep class * extends androidx.room.RoomDatabase { *; }
# ERA 32 R19：SQLCipher 4.17（net.zetetic）JNI 按全名注册，防 R8 混淆/剥离破坏原生绑定。
-keep class net.zetetic.database.** { *; }
-dontwarn org.json.**
