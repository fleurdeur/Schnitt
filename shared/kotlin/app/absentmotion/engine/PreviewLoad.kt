package app.absentmotion.engine

/** Work that must finish before the preview can show its real picture. */
enum class PreviewLoad {
    Assets,
    Effects,
    Text,
}
