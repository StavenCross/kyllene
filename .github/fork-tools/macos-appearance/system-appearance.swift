import AppKit
let name = NSApplication.shared.effectiveAppearance.bestMatch(from: [.aqua, .darkAqua])
print(name?.rawValue ?? "unknown")
