# O WorkManager (Room) cria o WorkDatabase_Impl por reflexao: sem o construtor o app crasha.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
