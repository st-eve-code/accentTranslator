import UIKit

final class KeyboardViewController: UIInputViewController {

    private enum Shift { case off, on, caps }
    private enum Page { case letters, symbols }

    private var engine: AccentEngine?
    private var accents: [AccentOption] = []

    private var pending: [Character] = []
    private var tokenStart = 0
    private var shift: Shift = .on
    private var page: Page = .letters

    private let brandColor = UIColor(red: 0.31, green: 0.43, blue: 0.97, alpha: 1)

    override func viewDidLoad() {
        super.viewDidLoad()
        loadAccents()
        buildKeyboard()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        loadAccents()
        rebuild()
    }

    private func loadAccents() {
        guard let url = Bundle.main.url(forResource: "accents", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8) else {
            accents = []
            engine = nil
            return
        }
        accents = AccentEngine.availableAccents(inJSON: json)
        engine = try? AccentEngine(catalogJSON: json, accentID: AccentStore.activeAccentID)
    }

    // MARK: - UI construction

    private func rebuild() {
        view.subviews.forEach { $0.removeFromSuperview() }
        buildKeyboard()
    }

    private func buildKeyboard() {
        view.backgroundColor = UIColor(white: 0.93, alpha: 1)

        let column = UIStackView()
        column.axis = .vertical
        column.spacing = 5
        column.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(column)

        NSLayoutConstraint.activate([
            column.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 3),
            column.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -3),
            column.bottomAnchor.constraint(equalTo: view.layoutMarginsGuide.bottomAnchor, constant: -2)
        ])
        column.heightAnchor.constraint(equalToConstant: 258).isActive = true

        column.addArrangedSubview(topStrip())

        if page == .letters {
            column.addArrangedSubview(charRow(Array("qwertyuiop")))
            column.addArrangedSubview(charRow(Array("asdfghjkl")))
            column.addArrangedSubview(homeRow())
        } else {
            column.addArrangedSubview(symbolRow(["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"]))
            column.addArrangedSubview(symbolRow(["@", "#", "$", "_", "&", "-", "+", "(", ")", "/"]))
            column.addArrangedSubview(symbolBottomRow())
        }

        column.addArrangedSubview(bottomRow())
        refreshShiftDisplay()
        refreshAccentButton()
    }

    private func makeButton(title: String, background: UIColor = .white, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.setTitleColor(.black, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 17)
        button.backgroundColor = background
        button.layer.cornerRadius = 6
        button.layer.borderWidth = 0.5
        button.layer.borderColor = UIColor(white: 0.78, alpha: 1).cgColor
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }

    private func horizontalStack(distribution: UIStackView.Distribution) -> UIStackView {
        let stack = UIStackView()
        stack.axis = .horizontal
        stack.distribution = distribution
        stack.spacing = 5
        return stack
    }

    private func topStrip() -> UIView {
        let stack = horizontalStack(distribution: .fill)
        stack.heightAnchor.constraint(equalToConstant: 34).isActive = true

        let pill = makeButton(title: engine?.accentLabel ?? "Accent") { [weak self] in
            self?.cycleAccent()
        }
        pill.setTitleColor(.white, for: .normal)
        pill.titleLabel?.font = .boldSystemFont(ofSize: 14)
        pill.backgroundColor = brandColor
        pill.layer.borderWidth = 0
        accentPill = pill

        let next = makeButton(title: "\u{21C4}") { [weak self] in
            self?.advanceToNextInputMode()
        }
        next.backgroundColor = UIColor(white: 0.85, alpha: 1)
        next.widthAnchor.constraint(equalToConstant: 56).isActive = true

        stack.addArrangedSubview(pill)
        stack.addArrangedSubview(next)
        return stack
    }

    private func charRow(_ chars: [Character]) -> UIView {
        let stack = horizontalStack(distribution: .fillEqually)
        stack.heightAnchor.constraint(equalToConstant: 44).isActive = true
        for char in chars {
            stack.addArrangedSubview(makeButton(title: String(char)) { [weak self] in
                self?.typeChar(char)
            })
        }
        return stack
    }

    private func homeRow() -> UIView {
        let stack = horizontalStack(distribution: .fillEqually)
        stack.heightAnchor.constraint(equalToConstant: 44).isActive = true

        shiftButton = makeButton(title: "\u{21E7}", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.cycleShift()
        }
        stack.addArrangedSubview(shiftButton!)

        for char in Array("zxcvbnm") {
            stack.addArrangedSubview(makeButton(title: String(char)) { [weak self] in
                self?.typeChar(char)
            })
        }

        let delete = makeButton(title: "\u{232B}", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.backspace()
        }
        stack.addArrangedSubview(delete)
        return stack
    }

    private func symbolRow(_ symbols: [String]) -> UIView {
        let stack = horizontalStack(distribution: .fillEqually)
        stack.heightAnchor.constraint(equalToConstant: 44).isActive = true
        for symbol in symbols {
            stack.addArrangedSubview(makeButton(title: symbol) { [weak self] in
                self?.rawInsert(symbol)
            })
        }
        return stack
    }

    private func symbolBottomRow() -> UIView {
        let stack = horizontalStack(distribution: .fillEqually)
        stack.heightAnchor.constraint(equalToConstant: 44).isActive = true

        let toggle = makeButton(title: "ABC", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.togglePage()
        }
        stack.addArrangedSubview(toggle)

        for symbol in ["*", "\"", "'", ":", ";", "!", "?", "%"] {
            stack.addArrangedSubview(makeButton(title: symbol) { [weak self] in
                self?.rawInsert(symbol)
            })
        }

        let delete = makeButton(title: "\u{232B}", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.backspace()
        }
        stack.addArrangedSubview(delete)
        return stack
    }

    private func bottomRow() -> UIView {
        let stack = horizontalStack(distribution: .fill)
        stack.heightAnchor.constraint(equalToConstant: 44).isActive = true

        pageToggle = makeButton(title: "123", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.togglePage()
        }
        pageToggle!.widthAnchor.constraint(equalToConstant: 62).isActive = true

        let comma = makeButton(title: ",", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.trigger(",", finishSentence: false)
        }
        comma.widthAnchor.constraint(equalToConstant: 44).isActive = true

        let space = makeButton(title: "space", background: .white) { [weak self] in
            self?.trigger(" ", finishSentence: false)
        }

        let period = makeButton(title: ".", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.trigger(".", finishSentence: true)
        }
        period.widthAnchor.constraint(equalToConstant: 44).isActive = true

        let enter = makeButton(title: "\u{21B5}", background: UIColor(white: 0.85, alpha: 1)) { [weak self] in
            self?.enter()
        }
        enter.widthAnchor.constraint(equalToConstant: 70).isActive = true

        [pageToggle!, comma, space, period, enter].forEach { stack.addArrangedSubview($0) }
        return stack
    }

    // MARK: - Text handling (mirrors Android AccentKeyboardService)

    private func typeChar(_ char: Character) {
        let output: Character
        switch shift {
        case .off: output = Character(char.lowercased())
        default: output = Character(char.uppercased())
        }
        textDocumentProxy?.insertText(String(output))
        pending.append(output)
        if shift == .on { shift = .off }
        refreshShiftDisplay()
    }

    private func rawInsert(_ text: String) {
        textDocumentProxy?.insertText(text)
        pending.append(contentsOf: text)
    }

    private func trigger(_ triggerText: String, finishSentence: Bool) {
        guard let proxy = textDocumentProxy else {
            pending.append(contentsOf: triggerText)
            return
        }
        if finishSentence {
            let raw = String(pending)
            if !raw.isEmpty {
                let converted = translate(raw)
                deleteBackward(count: raw.count, proxy: proxy)
                proxy.insertText(converted + triggerText)
            } else {
                proxy.insertText(triggerText)
            }
            pending.removeAll()
            tokenStart = 0
            shift = .on
        } else {
            let start = min(tokenStart, pending.count)
            let segment = String(pending[start...])
            let converted = segment.trimmingCharacters(in: .whitespaces).isEmpty ? segment : translate(segment)
            if converted != segment {
                deleteBackward(count: segment.count, proxy: proxy)
            }
            proxy.insertText(converted + triggerText)
            pending.replaceSubrange(start..., with: Array(converted))
            pending.append(contentsOf: triggerText)
            tokenStart = pending.count
        }
        refreshShiftDisplay()
    }

    private func backspace() {
        guard let proxy = textDocumentProxy else { return }
        proxy.deleteBackward()
        if let removed = pending.popLast() {
            if pending.count < tokenStart { tokenStart = pending.count }
            if removed == " " {
                tokenStart = indexAfterLastSpace()
            }
        }
    }

    private func indexAfterLastSpace() -> Int {
        if let idx = pending.lastIndex(of: " ") {
            return pending.distance(from: pending.startIndex, to: pending.index(after: idx))
        }
        return 0
    }

    private func deleteBackward(count: Int, proxy: UITextDocumentProxy) {
        for _ in 0..<count { proxy.deleteBackward() }
    }

    private func translate(_ text: String) -> String {
        engine?.translate(text) ?? text
    }

    private func enter() {
        trigger("", finishSentence: true)
        textDocumentProxy?.insertText("\n")
    }

    // MARK: - State controls

    private func cycleShift() {
        switch shift {
        case .off: shift = .on
        case .on: shift = .caps
        case .caps: shift = .off
        }
        refreshShiftDisplay()
    }

    private func togglePage() {
        page = page == .letters ? .symbols : .letters
        rebuild()
    }

    private func cycleAccent() {
        guard !accents.isEmpty else { return }
        let currentIndex = accents.firstIndex(where: { $0.id == engine?.accentID }) ?? -1
        let next = accents[(currentIndex + 1) % accents.count]
        AccentStore.activeAccentID = next.id
        reloadEngineForAccent(next.id)
        refreshAccentButton()
    }

    private func reloadEngineForAccent(_ id: String) {
        guard let url = Bundle.main.url(forResource: "accents", withExtension: "json"),
              let json = try? String(contentsOf: url, encoding: .utf8) else { return }
        engine = try? AccentEngine(catalogJSON: json, accentID: id)
    }

    private var shiftButton: UIButton?
    private var pageToggle: UIButton?
    private var accentPill: UIButton?

    private func refreshShiftDisplay() {
        guard let button = shiftButton else { return }
        let active = shift == .on || shift == .caps
        button.backgroundColor = active ? brandColor.withAlphaComponent(0.85) : UIColor(white: 0.85, alpha: 1)
        button.setTitleColor(active ? .white : .black, for: .normal)
        button.setTitle(shift == .caps ? "\u{21EA}" : "\u{21E7}", for: .normal)
    }

    private func refreshAccentButton() {
        accentPill?.setTitle(engine?.accentLabel ?? "Accent", for: .normal)
    }
}
