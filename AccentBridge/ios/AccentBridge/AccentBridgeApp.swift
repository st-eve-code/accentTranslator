import SwiftUI

@main
struct AccentBridgeApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {

    @State private var accents: [AccentOption] = []
    @State private var selected = AccentStore.activeAccentID
    @State private var sample = "You should know about it."
    @State private var preview = ""

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Active accent")) {
                    Picker("Accent", selection: $selected) {
                        ForEach(accents) { option in
                            Text(option.label).tag(option.id)
                        }
                    }
                    .pickerStyle(.inline)
                }

                Section(header: Text("Outgoing engine preview")) {
                    TextField("Type plain English", text: $sample)
                        .autocapitalization(.sentences)
                    Text(preview.isEmpty ? " " : preview)
                        .font(.body.bold())
                        .foregroundColor(.accentColor)
                }

                Section(header: Text("Setup"), footer: Text(setupFooter)) {
                    Text("1. Settings > General > Keyboard > Keyboards > Add New Keyboard > AccentBridge")
                    Text("2. Open the AccentBridge keyboard in any app; tap its accent pill to switch dialects")
                    Text("3. Incoming: share any message to \"Translate to English\" for an on-device bubble card")
                }
            }
            .navigationTitle("AccentBridge")
        }
        .navigationViewStyle(.stack)
        .onAppear(perform: load)
        .onChange(of: selected) { newValue in
            AccentStore.activeAccentID = newValue
            regenerate()
        }
        .onChange(of: sample) { _ in regenerate() }
    }

    private var setupFooter: String {
        "Everything runs on-device. ML Kit translation models download once per language, then work fully offline."
    }

    private func load() {
        if let url = Bundle.main.url(forResource: "accents", withExtension: "json"),
           let json = try? String(contentsOf: url, encoding: .utf8) {
            accents = AccentEngine.availableAccents(inJSON: json)
            if !accents.contains(where: { $0.id == selected }) {
                selected = accents.first?.id ?? selected
            }
        }
        regenerate()
    }

    private func regenerate() {
        guard let url = Bundle.main.url(forResource: "accents", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8),
              let engine = try? AccentEngine(catalogJSON: json, accentID: selected) else {
            preview = ""
            return
        }
        preview = engine.translate(sample)
    }
}
