package com.chaoseye.lyrilayer
import org.junit.Test
import org.junit.Assert.*
class MusicRulesTest {
 @Test fun recordings(){assertTrue(MusicRules.excluded("Recordings/Call/","123.m4a"));assertTrue(MusicRules.excluded("Call/","123.m4a"));assertTrue(MusicRules.excluded("Recordings/Voice Recorder/","a.m4a"));assertTrue(MusicRules.excluded("Music/","통화 녹음 123.m4a"));assertTrue(MusicRules.excluded("Music/","a.m4a",flag=true))}
 @Test fun samsungAndTphonePaths(){
  for(path in listOf("Recordings/Call/","Recordings/TPhoneCallRecords/","Recordings/Voice Recorder/","Recordings/Interpreter/","Recordings/Sounds/","TPhoneCallRecords/","/storage/emulated/0/Recordings/TPhoneCallRecords/","recordings\\tphonecallrecords\\")){
   assertTrue(path,MusicRules.excluded(path,"sample.m4a"))
  }
 }
 @Test fun tphoneFilenameWithoutPath(){
  assertTrue(MusicRules.excluded("","01000000000_20261117102533.m4a"))
  assertTrue(MusicRules.excluded("","Example_01000000000_20261113212120.m4a"))
  assertTrue(MusicRules.excluded("","","Example_01000000000_20261113212120"))
  assertTrue(MusicRules.excluded("","통화 녹음 000_220203_164942.m4a"))
  assertFalse(MusicRules.excluded("Music/","Example_20261117102533.m4a"))
  assertFalse(MusicRules.excluded("Music/","Song_01.m4a"))
  assertFalse(MusicRules.excluded("Music/","Artist - Song.m4a"))
 }
 @Test fun recordingSubstringInPath(){
  for(path in listOf("Recording/","Recordings/","Music/MyRecording/","/storage/emulated/0/RECORDING/Call/song.m4a","Download/old_recording_backup/song.mp3","Music/recording-track.m4a")){
   assertTrue(path,MusicRules.excluded(path,"sample.m4a"))
  }
  assertFalse(MusicRules.excluded("Music/Album/song.m4a","song.m4a"))
  assertFalse(MusicRules.excluded("Download/","song.mp3"))
 }
 @Test fun music(){assertFalse(MusicRules.excluded("Music/Album/","track.m4a"));assertFalse(MusicRules.excluded("Download/","Call Me Maybe.mp3"))}
 @Test fun filename(){assertTrue(MusicRules.identities("01 - Artist - Song.mp3","<unknown>","01 - Artist - Song.mp3").contains(MusicRules.Identity("Song","Artist")))}
 @Test fun matching(){val id=MusicRules.Identity("밤편지","아이유 (IU)");assertNotNull(MusicRules.score(id,"",240000,"밤편지","IU","",241.0,true));assertNull(MusicRules.score(id,"",240000,"밤편지","Other","",241.0,true));assertNull(MusicRules.score(id,"",240000,"밤편지","IU","",260.0,true))}
 @Test fun unknownArtist(){val id=MusicRules.Identity("Song","");assertNull(MusicRules.score(id,"",0,"Song","Artist","",240.0,true));assertNotNull(MusicRules.score(id,"",240000,"Song","Artist","",241.0,true))}
}
