# Java Multi-Client Messaging System

## Build
    cd messaging-system
    mkdir out
    javac -encoding UTF-8 -d out $(find src -name "*.java")      # Windows: javac -encoding UTF-8 -d out src\common\*.java src\server\*.java src\client\*.java

## Run (each in its own terminal)
    java -cp out server.ChatServer 5000
    java -cp out client.ChatClient localhost 5000      # run several, from different folders if on the same PC

Windows console + Arabic: run `chcp 65001` first. Each client writes `history_<user>.txt`
and `downloads_<user>/` in its current working directory.

## Client commands
    /msg <user> <text>        /create <group>     /join <group>     /leave <group>
    /gmsg <group> <text>      /users              /sendfile <user> <path>
    /history                  /search <phrase>    /help             /quit

## Protocol (for the documentation)
Text frame : [4-byte length][UTF-8 "TYPE|SENDER|RECIPIENT|CONTENT"]  (split on first 3 '|' only)
File send  : FILE_SEND|me|user|name:size -> server answers FILE_READY or FILE_DENIED
             -> chunks [len>0][bytes]... then [0]=end of file, [-1]=abort
             -> receiver verifies size, replies FILE_RESULT|OK:.. / FAIL:..
