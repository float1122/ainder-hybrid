import AinderHybridCore
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if model.joining {
                NavigationStack { JoinView() }
            } else if model.active == nil {
                NavigationStack { WelcomeView() }
            } else {
                TabView(selection: Binding(get: { model.tab }, set: { model.select($0) })) {
                    NavigationStack { DeckView() }.tabItem { Text("Deck") }.tag(AppTab.deck)
                    NavigationStack { MatchesView() }.tabItem { Text("Matches") }.tag(AppTab.matches)
                    NavigationStack { MyAiView() }.tabItem { Text("My AI") }.tag(AppTab.myAI)
                    NavigationStack { LogView() }.tabItem { Text("Hybrid log") }.tag(AppTab.log)
                }
            }
        }
        .tint(Palette.rose)
        .overlay(alignment: .top) {
            if model.busy { ProgressView().padding(6).accessibilityIdentifier("busy") }
        }
        .alert(model.message ?? "", isPresented: Binding(get: { model.message != nil }, set: { if !$0 { model.message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }
}

struct LogView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let entries = model.logEntries
        let privateCloud = entries.filter { $0.task.privacy == .private }.reduce(0) { $0 + $1.cloudChars }
        let publicCloud = entries.filter { $0.task.privacy == .public }.reduce(0) { $0 + $1.cloudChars }
        List {
            Text("Characters sent to the cloud: private tasks \(privateCloud) · public tasks \(publicCloud)")
                .font(.footnote).accessibilityIdentifier("log-totals")
            ForEach(entries) { e in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(e.at.formatted(date: .omitted, time: .standard))  \(e.task.label)").font(.callout)
                        Text("\(e.ms) ms · \(e.cloudChars) chars to cloud\(e.note.isEmpty ? "" : " · \(e.note)")").font(.caption2)
                    }
                    Spacer()
                    SourceBadge(source: e.source)
                }
            }
        }
        .navigationTitle("Hybrid log")
    }
}
