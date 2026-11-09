' CAMA silent launcher - double-click it to start without a console window.
Option Explicit

Dim fso, shell, base, javaHome, javaExe, command
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")

base = fso.GetParentFolderName(WScript.ScriptFullName)
shell.CurrentDirectory = base

' Build silently on first run. Show a message only if the build fails.
If Not fso.FileExists(base & "\out\com\cama\Main.class") Then
    If shell.Run("cmd /c """ & base & "\build.bat""", 0, True) <> 0 Then
        MsgBox "CAMA build failed. Run build.bat to see details.", 16, "CAMA"
        WScript.Quit 1
    End If
End If

javaHome = shell.ExpandEnvironmentStrings("%JAVA_HOME%")
If javaHome = "%JAVA_HOME%" Then javaHome = "D:\Compilation_environment\jdk8"
javaExe = javaHome & "\bin\javaw.exe"
If Not fso.FileExists(javaExe) Then javaExe = "javaw"

command = """" & javaExe & """ -Dfile.encoding=UTF-8 -cp ""out;lib\*"" com.cama.Main"
On Error Resume Next
shell.Run command, 0, False
If Err.Number <> 0 Then
    MsgBox "Failed to start CAMA: " & Err.Description, 16, "CAMA"
End If
