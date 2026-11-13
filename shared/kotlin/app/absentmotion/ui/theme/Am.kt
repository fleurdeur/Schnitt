package app.absentmotion.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class Palette(
    val TopBar: Color,
    val PreviewBg: Color,
    val TimelineBg: Color,
    val Panel: Color,
    val PanelInset: Color,
    val Button: Color,
    val Field: Color,
    val FieldHi: Color,
    val Chosen: Color,
    val Well: Color,
    val Deepest: Color,
    val Line: Color,
    val MenuLine: Color,
    val KeyDigit: Color,
    val KeySymbol: Color,
    val Scrim: Color,
    val PressEditor: Color,
    val OnDark: Color,
    val OnDarkDim: Color,
    val Caption: Color,
    val Faint: Color,
    val Inactive: Color,
    val OnScrim: Color,
    val Accent: Color,
    val AccentLit: Color,
    val AccentGlow: Color,
    val AccentDeep: Color,
    val OnAccent: Color,
    val Cool: Color,
    val CoolDeep: Color,
    val Gold: Color,
    val Warning: Color,
    val Bookmark: Color,
    val LayerBar: Color,
    val LayerBarStripe: Color,
    val TextBar: Color,
    val TextBarStripe: Color,
    val AudioBar: Color,
    val AudioWave: Color,
    val OnBar: Color,
    val OnAudioBar: Color,
    val HomeBg: Color,
    val Card: Color,
    val SheetBg: Color,
    val Navy: Color,
    val HomeAccent: Color,
    val OnNavy: Color,
    val OnNavyAccent: Color,
    val NavyHi: Color,
    val NavyLine: Color,
    val HomeInk: Color,
    val HomeMid: Color,
    val HomeDim: Color,
    val HomeFaint: Color,
    val HomeLine: Color,
    val HomePanel: Color,
    val HomeDrawer: Color,
    val SwitchOff: Color,
    val Thumb: Color,
    val MenuScrim: Color,
    val PressHome: Color,
) {
    fun toMap(): Map<String, Color> = linkedMapOf(
        "TopBar" to TopBar,
        "PreviewBg" to PreviewBg,
        "TimelineBg" to TimelineBg,
        "Panel" to Panel,
        "PanelInset" to PanelInset,
        "Button" to Button,
        "Field" to Field,
        "FieldHi" to FieldHi,
        "Chosen" to Chosen,
        "Well" to Well,
        "Deepest" to Deepest,
        "Line" to Line,
        "MenuLine" to MenuLine,
        "KeyDigit" to KeyDigit,
        "KeySymbol" to KeySymbol,
        "Scrim" to Scrim,
        "PressEditor" to PressEditor,
        "OnDark" to OnDark,
        "OnDarkDim" to OnDarkDim,
        "Caption" to Caption,
        "Faint" to Faint,
        "Inactive" to Inactive,
        "OnScrim" to OnScrim,
        "Accent" to Accent,
        "AccentLit" to AccentLit,
        "AccentGlow" to AccentGlow,
        "AccentDeep" to AccentDeep,
        "OnAccent" to OnAccent,
        "Cool" to Cool,
        "CoolDeep" to CoolDeep,
        "Gold" to Gold,
        "Warning" to Warning,
        "Bookmark" to Bookmark,
        "LayerBar" to LayerBar,
        "LayerBarStripe" to LayerBarStripe,
        "TextBar" to TextBar,
        "TextBarStripe" to TextBarStripe,
        "AudioBar" to AudioBar,
        "AudioWave" to AudioWave,
        "OnBar" to OnBar,
        "OnAudioBar" to OnAudioBar,
        "HomeBg" to HomeBg,
        "Card" to Card,
        "SheetBg" to SheetBg,
        "Navy" to Navy,
        "HomeAccent" to HomeAccent,
        "OnNavy" to OnNavy,
        "OnNavyAccent" to OnNavyAccent,
        "NavyHi" to NavyHi,
        "NavyLine" to NavyLine,
        "HomeInk" to HomeInk,
        "HomeMid" to HomeMid,
        "HomeDim" to HomeDim,
        "HomeFaint" to HomeFaint,
        "HomeLine" to HomeLine,
        "HomePanel" to HomePanel,
        "HomeDrawer" to HomeDrawer,
        "SwitchOff" to SwitchOff,
        "Thumb" to Thumb,
        "MenuScrim" to MenuScrim,
        "PressHome" to PressHome,
    )

    companion object {
        val Names: List<String> = listOf(
            "TopBar",
            "PreviewBg",
            "TimelineBg",
            "Panel",
            "PanelInset",
            "Button",
            "Field",
            "FieldHi",
            "Chosen",
            "Well",
            "Deepest",
            "Line",
            "MenuLine",
            "KeyDigit",
            "KeySymbol",
            "Scrim",
            "PressEditor",
            "OnDark",
            "OnDarkDim",
            "Caption",
            "Faint",
            "Inactive",
            "OnScrim",
            "Accent",
            "AccentLit",
            "AccentGlow",
            "AccentDeep",
            "OnAccent",
            "Cool",
            "CoolDeep",
            "Gold",
            "Warning",
            "Bookmark",
            "LayerBar",
            "LayerBarStripe",
            "TextBar",
            "TextBarStripe",
            "AudioBar",
            "AudioWave",
            "OnBar",
            "OnAudioBar",
            "HomeBg",
            "Card",
            "SheetBg",
            "Navy",
            "HomeAccent",
            "OnNavy",
            "OnNavyAccent",
            "NavyHi",
            "NavyLine",
            "HomeInk",
            "HomeMid",
            "HomeDim",
            "HomeFaint",
            "HomeLine",
            "HomePanel",
            "HomeDrawer",
            "SwitchOff",
            "Thumb",
            "MenuScrim",
            "PressHome",
        )

        fun from(colours: Map<String, Color>, base: Palette = Schnitt): Palette = Palette(
            TopBar = colours["TopBar"] ?: base.TopBar,
            PreviewBg = colours["PreviewBg"] ?: base.PreviewBg,
            TimelineBg = colours["TimelineBg"] ?: base.TimelineBg,
            Panel = colours["Panel"] ?: base.Panel,
            PanelInset = colours["PanelInset"] ?: base.PanelInset,
            Button = colours["Button"] ?: base.Button,
            Field = colours["Field"] ?: base.Field,
            FieldHi = colours["FieldHi"] ?: base.FieldHi,
            Chosen = colours["Chosen"] ?: base.Chosen,
            Well = colours["Well"] ?: base.Well,
            Deepest = colours["Deepest"] ?: base.Deepest,
            Line = colours["Line"] ?: base.Line,
            MenuLine = colours["MenuLine"] ?: base.MenuLine,
            KeyDigit = colours["KeyDigit"] ?: base.KeyDigit,
            KeySymbol = colours["KeySymbol"] ?: base.KeySymbol,
            Scrim = colours["Scrim"] ?: base.Scrim,
            PressEditor = colours["PressEditor"] ?: base.PressEditor,
            OnDark = colours["OnDark"] ?: base.OnDark,
            OnDarkDim = colours["OnDarkDim"] ?: base.OnDarkDim,
            Caption = colours["Caption"] ?: base.Caption,
            Faint = colours["Faint"] ?: base.Faint,
            Inactive = colours["Inactive"] ?: base.Inactive,
            OnScrim = colours["OnScrim"] ?: base.OnScrim,
            Accent = colours["Accent"] ?: base.Accent,
            AccentLit = colours["AccentLit"] ?: base.AccentLit,
            AccentGlow = colours["AccentGlow"] ?: base.AccentGlow,
            AccentDeep = colours["AccentDeep"] ?: base.AccentDeep,
            OnAccent = colours["OnAccent"] ?: base.OnAccent,
            Cool = colours["Cool"] ?: base.Cool,
            CoolDeep = colours["CoolDeep"] ?: base.CoolDeep,
            Gold = colours["Gold"] ?: base.Gold,
            Warning = colours["Warning"] ?: base.Warning,
            Bookmark = colours["Bookmark"] ?: base.Bookmark,
            LayerBar = colours["LayerBar"] ?: base.LayerBar,
            LayerBarStripe = colours["LayerBarStripe"] ?: base.LayerBarStripe,
            TextBar = colours["TextBar"] ?: base.TextBar,
            TextBarStripe = colours["TextBarStripe"] ?: base.TextBarStripe,
            AudioBar = colours["AudioBar"] ?: base.AudioBar,
            AudioWave = colours["AudioWave"] ?: base.AudioWave,
            OnBar = colours["OnBar"] ?: base.OnBar,
            OnAudioBar = colours["OnAudioBar"] ?: base.OnAudioBar,
            HomeBg = colours["HomeBg"] ?: base.HomeBg,
            Card = colours["Card"] ?: base.Card,
            SheetBg = colours["SheetBg"] ?: base.SheetBg,
            Navy = colours["Navy"] ?: base.Navy,
            HomeAccent = colours["HomeAccent"] ?: base.HomeAccent,
            OnNavy = colours["OnNavy"] ?: base.OnNavy,
            OnNavyAccent = colours["OnNavyAccent"] ?: base.OnNavyAccent,
            NavyHi = colours["NavyHi"] ?: base.NavyHi,
            NavyLine = colours["NavyLine"] ?: base.NavyLine,
            HomeInk = colours["HomeInk"] ?: base.HomeInk,
            HomeMid = colours["HomeMid"] ?: base.HomeMid,
            HomeDim = colours["HomeDim"] ?: base.HomeDim,
            HomeFaint = colours["HomeFaint"] ?: base.HomeFaint,
            HomeLine = colours["HomeLine"] ?: base.HomeLine,
            HomePanel = colours["HomePanel"] ?: base.HomePanel,
            HomeDrawer = colours["HomeDrawer"] ?: base.HomeDrawer,
            SwitchOff = colours["SwitchOff"] ?: base.SwitchOff,
            Thumb = colours["Thumb"] ?: base.Thumb,
            MenuScrim = colours["MenuScrim"] ?: base.MenuScrim,
            PressHome = colours["PressHome"] ?: base.PressHome,
        )

        val Schnitt = Palette(
            TopBar = Color(0xFF050706),
            PreviewBg = Color(0xFF000000),
            TimelineBg = Color(0xFF080B08),
            Panel = Color(0xFF0B0F0C),
            PanelInset = Color(0xFF101510),
            Button = Color(0xFF141A15),
            Field = Color(0xFF182019),
            FieldHi = Color(0xFF223026),
            Chosen = Color(0xFF2C3D31),
            Well = Color(0xFF070A08),
            Deepest = Color(0xFF000000),
            Line = Color(0xFF1B241D),
            MenuLine = Color(0xFF162019),
            KeyDigit = Color(0xFF2D4033),
            KeySymbol = Color(0xFF1C2A20),
            Scrim = Color(0xF2000000),
            PressEditor = Color(0x267ED9A0),
            OnDark = Color(0xFFE6F0E7),
            OnDarkDim = Color(0xFF9CB2A0),
            Caption = Color(0xFFC4D8C8),
            Faint = Color(0xFF718678),
            Inactive = Color(0xFF4C5B50),
            OnScrim = Color(0xFFFFFFFF),
            Accent = Color(0xFF7ED9A0),
            AccentLit = Color(0xFFA6E8BC),
            AccentGlow = Color(0xFFCFF5DC),
            AccentDeep = Color(0xFF123522),
            OnAccent = Color(0xFF04140B),
            Cool = Color(0xFF6FD3C7),
            CoolDeep = Color(0xFF1D4038),
            Gold = Color(0xFFE8C979),
            Warning = Color(0xFFF08A8A),
            Bookmark = Color(0xFFE68BB5),
            LayerBar = Color(0xFF4FAE8A),
            LayerBarStripe = Color(0xFF439C7A),
            TextBar = Color(0xFFD9B95C),
            TextBarStripe = Color(0xFFC9AA4F),
            AudioBar = Color(0xFF4E7D64),
            AudioWave = Color(0xFFC7E8D5),
            OnBar = Color(0xFF04140B),
            OnAudioBar = Color(0xFFFFFFFF),
            HomeBg = Color(0xFF000000),
            Card = Color(0xFF0A0D0A),
            SheetBg = Color(0xFF050705),
            Navy = Color(0xFFE8F2EA),
            HomeAccent = Color(0xFF7ED9A0),
            OnNavy = Color(0xFF04140B),
            OnNavyAccent = Color(0xFFB9F3CC),
            NavyHi = Color(0xFFB9CDBD),
            NavyLine = Color(0xFF7E9485),
            HomeInk = Color(0xFFE6F0E7),
            HomeMid = Color(0xFF9CB2A0),
            HomeDim = Color(0xFF7D9183),
            HomeFaint = Color(0xFF4C5B50),
            HomeLine = Color(0xFF1B241D),
            HomePanel = Color(0xFF0B0F0C),
            HomeDrawer = Color(0xFF050705),
            SwitchOff = Color(0xFF344138),
            Thumb = Color(0xFF141A15),
            MenuScrim = Color(0xE6000000),
            PressHome = Color(0x267ED9A0),
        )

        val Dark = Schnitt.copy(
            HomeBg = Color(0xFF000000),
            Card = Color(0xFF0A0D0A),
            SheetBg = Color(0xFF050705),
            Navy = Color(0xFFE8F2EA),
            HomeAccent = Color(0xFFA6E8BC),
            OnNavy = Color(0xFF04140B),
            OnNavyAccent = Color(0xFFB9F3CC),
            NavyHi = Color(0xFFB9CDBD),
            NavyLine = Color(0xFF7E9485),
            HomeInk = Color(0xFFE6F0E7),
            HomeMid = Color(0xFF9CB2A0),
            HomeDim = Color(0xFF7D9183),
            HomeFaint = Color(0xFF4C5B50),
            HomeLine = Color(0xFF1B241D),
            HomePanel = Color(0xFF0B0F0C),
            HomeDrawer = Color(0xFF050705),
            SwitchOff = Color(0xFF344138),
            Thumb = Color(0xFF141A15),
            MenuScrim = Color(0xE6000000),
            PressHome = Color(0x267ED9A0),
        )

        val Daylight = Palette(
            TopBar = Color(0xFFFFFFFF),
            PreviewBg = Color(0xFFEEF3EE),
            TimelineBg = Color(0xFFF1F5F1),
            Panel = Color(0xFFF8FBF8),
            PanelInset = Color(0xFFEEF4EF),
            Button = Color(0xFFE3EDE5),
            Field = Color(0xFFEDF4EF),
            FieldHi = Color(0xFFE1ECE3),
            Chosen = Color(0xFFC9E3D0),
            Well = Color(0xFFE8F0EA),
            Deepest = Color(0xFFC6CBD4),
            Line = Color(0xFFD3E1D6),
            MenuLine = Color(0xFFE0EBE2),
            KeyDigit = Color(0xFFE9ECF1),
            KeySymbol = Color(0xFFDDE1E8),
            Scrim = Color(0xF2121317),
            PressEditor = Color(0x12000000),
            OnDark = Color(0xFF122018),
            OnDarkDim = Color(0xFF3F5A49),
            Caption = Color(0xFF2C4637),
            Faint = Color(0xFF566F5E),
            Inactive = Color(0xFF8FA99A),
            OnScrim = Color(0xFFFFFFFF),
            Accent = Color(0xFF2E8B57),
            AccentLit = Color(0xFF2F9E5F),
            AccentGlow = Color(0xFF1E6B40),
            AccentDeep = Color(0xFFD6F0DF),
            OnAccent = Color(0xFFFFFFFF),
            Cool = Color(0xFF1A5BA8),
            CoolDeep = Color(0xFFBCD6F4),
            Gold = Color(0xFFA56A00),
            Warning = Color(0xFFB91F28),
            Bookmark = Color(0xFFD0357F),
            LayerBar = Color(0xFF5BB4A0),
            LayerBarStripe = Color(0xFF52AB97),
            TextBar = Color(0xFFE8B64C),
            TextBarStripe = Color(0xFFDFAC40),
            AudioBar = Color(0xFF6A58C8),
            AudioWave = Color(0xFFD2CBF7),
            OnBar = Color(0xFF121316),
            OnAudioBar = Color(0xFFFFFFFF),
            HomeBg = Color(0xFFF4F8F4),
            Card = Color(0xFFFFFFFF),
            SheetBg = Color(0xFFEAF2EB),
            Navy = Color(0xFF173024),
            HomeAccent = Color(0xFF2E8B57),
            OnNavy = Color(0xFFFFFFFF),
            OnNavyAccent = Color(0xFF8FE0A9),
            NavyHi = Color(0xFF385544),
            NavyLine = Color(0xFF2D3139),
            HomeInk = Color(0xFF122018),
            HomeMid = Color(0xFF3F5A49),
            HomeDim = Color(0xFF4A6654),
            HomeFaint = Color(0xFF9DB5A6),
            HomeLine = Color(0xFFD3E1D6),
            HomePanel = Color(0xFFEDF4EF),
            HomeDrawer = Color(0xFFFAFCFA),
            SwitchOff = Color(0xFFB5C9BB),
            Thumb = Color(0xFFF2F8F3),
            MenuScrim = Color(0xCCF4F8F4),
            PressHome = Color(0x12000000),
        )

        val BuiltIn: List<Pair<String, Palette>> = listOf("Schnitt" to Schnitt, "Dark" to Dark, "Daylight" to Daylight)
    }
}

object Am {
    var palette: Palette by mutableStateOf(Palette.Schnitt)

    val editorIsLight: Boolean get() = palette.TopBar.luminance() > 0.5f
    val homeIsLight: Boolean get() = palette.HomeBg.luminance() > 0.5f
    val OnScrimAccent: Color get() = if (homeIsLight) palette.OnNavyAccent else palette.HomeAccent

    val TopBar: Color get() = palette.TopBar
    val PreviewBg: Color get() = palette.PreviewBg
    val TimelineBg: Color get() = palette.TimelineBg
    val Panel: Color get() = palette.Panel
    val PanelInset: Color get() = palette.PanelInset
    val Button: Color get() = palette.Button
    val Field: Color get() = palette.Field
    val FieldHi: Color get() = palette.FieldHi
    val CardHeader: Color get() = palette.FieldHi
    val Chosen: Color get() = palette.Chosen
    val Well: Color get() = palette.Well
    val Deepest: Color get() = palette.Deepest
    val Line: Color get() = palette.Line
    val MenuLine: Color get() = palette.MenuLine
    val KeyDigit: Color get() = palette.KeyDigit
    val KeySymbol: Color get() = palette.KeySymbol
    val Scrim: Color get() = palette.Scrim
    val PressEditor: Color get() = palette.PressEditor

    val OnDark: Color get() = palette.OnDark
    val OnDarkDim: Color get() = palette.OnDarkDim
    val Caption: Color get() = palette.Caption
    val Faint: Color get() = palette.Faint
    val Inactive: Color get() = palette.Inactive
    val OnScrim: Color get() = palette.OnScrim

    val Accent: Color get() = palette.Accent
    val AccentLit: Color get() = palette.AccentLit
    val AccentGlow: Color get() = palette.AccentGlow
    val AccentDeep: Color get() = palette.AccentDeep
    val OnAccent: Color get() = palette.OnAccent

    val Cool: Color get() = palette.Cool
    val CoolDeep: Color get() = palette.CoolDeep
    val Gold: Color get() = palette.Gold
    val Warning: Color get() = palette.Warning
    val Bookmark: Color get() = palette.Bookmark
    val LayerBar: Color get() = palette.LayerBar
    val LayerBarStripe: Color get() = palette.LayerBarStripe
    val TextBar: Color get() = palette.TextBar
    val TextBarStripe: Color get() = palette.TextBarStripe
    val AudioBar: Color get() = palette.AudioBar
    val AudioWave: Color get() = palette.AudioWave
    val OnBar: Color get() = palette.OnBar
    val OnAudioBar: Color get() = palette.OnAudioBar

    val HomeBg: Color get() = palette.HomeBg
    val Card: Color get() = palette.Card
    val SheetBg: Color get() = palette.SheetBg
    val Navy: Color get() = palette.Navy
    val HomeAccent: Color get() = palette.HomeAccent
    val OnNavy: Color get() = palette.OnNavy
    val OnNavyAccent: Color get() = palette.OnNavyAccent
    val NavyHi: Color get() = palette.NavyHi
    val NavyLine: Color get() = palette.NavyLine
    val HomeInk: Color get() = palette.HomeInk
    val HomeMid: Color get() = palette.HomeMid
    val HomeDim: Color get() = palette.HomeDim
    val HomeFaint: Color get() = palette.HomeFaint
    val HomeLine: Color get() = palette.HomeLine
    val HomePanel: Color get() = palette.HomePanel
    val HomeDrawer: Color get() = palette.HomeDrawer
    val SwitchOff: Color get() = palette.SwitchOff
    val Thumb: Color get() = palette.Thumb
    val MenuScrim: Color get() = palette.MenuScrim
    val PressHome: Color get() = palette.PressHome
}
