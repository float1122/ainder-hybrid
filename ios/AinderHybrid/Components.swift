import AinderHybridCore
import SwiftUI

// Palette: ink + rose for the dating side, teal for "stays on device", amber for "cloud".
enum Palette {
    static let rose = Color(red: 0.85, green: 0.27, blue: 0.42)
    static let device = Color(red: 0.06, green: 0.48, blue: 0.42)
    static let cloud = Color(red: 0.71, green: 0.41, blue: 0.05)
    static let noAI = Color(red: 0.42, green: 0.40, blue: 0.44)
}

extension Source {
    var color: Color {
        switch self {
        case .onDevice: Palette.device
        case .inCloud: Palette.cloud
        case .noAI: Palette.noAI
        }
    }
}

/// Small text-only badge on every AI result: "On-device", "Cloud" or "No AI".
struct SourceBadge: View {
    let source: Source
    var body: some View {
        Text(source.badge)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(source.color)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .overlay(Capsule().stroke(source.color, lineWidth: 1))
            .accessibilityIdentifier("badge-\(source.rawValue)")
    }
}

/// Labelled AI result with its source badge.
struct AiResultRow: View {
    let label: String
    let source: Source
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(label).font(.subheadline.weight(.medium))
                SourceBadge(source: source)
            }
            if !text.isEmpty { Text(text).font(.callout) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Marks private, on-device-only sections.
struct StaysOnPhone: View {
    var body: some View {
        Text("Stays on this phone")
            .font(.caption2.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(Palette.device, in: RoundedRectangle(cornerRadius: 4))
    }
}

struct CardBox<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 14))
    }
}
