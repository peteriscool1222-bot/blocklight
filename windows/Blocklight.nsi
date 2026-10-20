; Blocklight — Windows setup script (NSIS)
; Build:  ./build-installer.sh        (runs makensis from the project's windows/ dir)
;
; Asks for:
;   1. Blocklight installation directory
;   2. Minecraft installation directory (versions/mods/assets) -> persisted into
;      %USERPROFILE%\.blocklight\settings.json as gameDir (only created when
;      no existing settings file is present, so user settings are never overwritten).

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"

!define PRODUCT_NAME    "Blocklight"
!define PRODUCT_VERSION "1.0"
!define PRODUCT_PUBLISHER "Blocklight"

Name "${PRODUCT_NAME} ${PRODUCT_VERSION}"
OutFile "..\out\Blocklight-Setup-${PRODUCT_VERSION}-x64.exe"
InstallDir "$LOCALAPPDATA\Programs\${PRODUCT_NAME}"
InstallDirRegKey HKCU "Software\${PRODUCT_NAME}" "InstallDir"
RequestExecutionLevel user
Unicode true
SetCompressor /SOLID lzma

VIProductVersion "1.0.0.0"
VIAddVersionKey /LANG=0 "ProductName" "${PRODUCT_NAME}"
VIAddVersionKey /LANG=0 "ProductVersion" "${PRODUCT_VERSION}"
VIAddVersionKey /LANG=0 "FileVersion" "${PRODUCT_VERSION}.0"
VIAddVersionKey /LANG=0 "CompanyName" "${PRODUCT_PUBLISHER}"
VIAddVersionKey /LANG=0 "LegalCopyright" "© 2026 ${PRODUCT_PUBLISHER}"
VIAddVersionKey /LANG=0 "FileDescription" "${PRODUCT_NAME} Setup"

; ----------------------------------------------------------------- pages
; (add !define MUI_ICON "blocklight.ico" here if an .ico is ever provided)
!define MUI_ICON "icon.ico"
!define MUI_UNICON "icon.ico"
!define MUI_FINISHPAGE_RUN_TEXT "Launch Blocklight"
!define MUI_FINISHPAGE_RUN "$INSTDIR\runtime\bin\javaw.exe"
!define MUI_FINISHPAGE_RUN_PARAMETERS "--enable-native-access=ALL-UNNAMED -jar $\"$INSTDIR\Blocklight.jar$\""

!insertmacro MUI_PAGE_DIRECTORY           ; 1. where to install Blocklight
Page custom fGameDirSelect fGameDirLeave  ; 2. where Minecraft game files go
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Var GameDirEdit
Var GameDir
Var GameDirJson

; ------------------------------------------- custom page: minecraft dir
Function fGameDirSelect
  !insertmacro MUI_HEADER_TEXT "Minecraft installation directory" \
      "Choose where Minecraft game files (versions, libraries, assets, mods) are stored."
  nsDialogs::Create 1018
  Pop $0

  ${NSD_CreateLabel} 0 0 100% 28u \
      "Blocklight keeps every version in its own subfolder, so vanilla, Fabric and Forge can be installed side by side. This can be any folder — even a portable one on an external drive. You can change it later in Settings."
  Pop $0

  ${NSD_CreateLabel} 0 36u 100% 10u "Minecraft installation directory:"
  Pop $0

  ${NSD_CreateText} 0 50u 76% 13u "$PROFILE\.blocklight\game"
  Pop $1
  StrCpy $GameDirEdit $1

  ${NSD_CreateButton} 80% 50u 20% 13u "Browse..."
  Pop $2
  ${NSD_OnClick} $2 fGameDirBrowse

  ${NSD_CreateLabel} 0 70u 100% 10u "Leave as-is for the default location."
  Pop $0

  nsDialogs::Show
FunctionEnd

Function fGameDirBrowse
  nsDialogs::SelectFolderDialog "Select Minecraft installation directory" "$PROFILE\.blocklight\game"
  Pop $0
  ${If} $0 != error
    SendMessage $GameDirEdit ${WM_SETTEXT} 0 "STR:$0"
  ${EndIf}
FunctionEnd

Function fGameDirLeave
  ${NSD_GetText} $GameDirEdit $GameDir
  ${If} $GameDir == ""
    MessageBox MB_ICONEXCLAMATION "Please enter a Minecraft installation directory, or keep the default."
    Abort
  ${EndIf}
  ; JSON needs forward slashes (backslashes would have to be escaped) — valid on Windows
  StrCpy $GameDirJson ""
  StrCpy $0 0
  ${Do}
    StrCpy $1 $GameDir 1 $0
    ${If} $1 == ""
      ${Break}
    ${EndIf}
    ${If} $1 == "\"
      StrCpy $1 "/"
    ${EndIf}
    StrCpy $GameDirJson "$GameDirJson$1"
    IntOp $0 $0 + 1
  ${Loop}
FunctionEnd

; ----------------------------------------------------------------- install
Section "Blocklight"
  SetOutPath $INSTDIR
  File "staging\Blocklight.jar"
  File "icon.ico"                 ; glowstone icon for shortcuts
  File /r "staging\runtime"          ; bundled Windows JRE (Temurin 21)

  ; shortcuts: run javaw so no console window appears; icon comes from icon.ico
  ; (pointing at javaw.exe would show the Java coffee cup)
  CreateDirectory "$SMPROGRAMS\${PRODUCT_NAME}"
  CreateShortCut "$SMPROGRAMS\${PRODUCT_NAME}\${PRODUCT_NAME}.lnk" \
      "$INSTDIR\runtime\bin\javaw.exe" \
      '--enable-native-access=ALL-UNNAMED -jar "$INSTDIR\Blocklight.jar"' \
      "$INSTDIR\icon.ico" 0
  CreateShortCut "$SMPROGRAMS\${PRODUCT_NAME}\Uninstall.lnk" "$INSTDIR\Uninstall.exe"
  CreateShortCut "$DESKTOP\${PRODUCT_NAME}.lnk" \
      "$INSTDIR\runtime\bin\javaw.exe" \
      '--enable-native-access=ALL-UNNAMED -jar "$INSTDIR\Blocklight.jar"' \
      "$INSTDIR\icon.ico" 0

  ; persist the chosen Minecraft directory (never clobber existing settings)
  ${IfNot} ${FileExists} "$PROFILE\.blocklight\settings.json"
    CreateDirectory "$PROFILE\.blocklight"
    FileOpen $0 "$PROFILE\.blocklight\settings.json" w
    FileWrite $0 '{"gameDir":"$GameDirJson"}'
    FileClose $0
  ${EndIf}

  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\${PRODUCT_NAME}" "InstallDir" "$INSTDIR"
SectionEnd

; --------------------------------------------------------------- uninstall
Section "Uninstall"
  Delete "$SMPROGRAMS\${PRODUCT_NAME}\${PRODUCT_NAME}.lnk"
  Delete "$SMPROGRAMS\${PRODUCT_NAME}\Uninstall.lnk"
  RMDir  "$SMPROGRAMS\${PRODUCT_NAME}"
  Delete "$DESKTOP\${PRODUCT_NAME}.lnk"
  RMDir /r "$INSTDIR"
  DeleteRegKey HKCU "Software\${PRODUCT_NAME}"
  ; note: %USERPROFILE%\.blocklight (accounts, settings, game files) is kept
SectionEnd
