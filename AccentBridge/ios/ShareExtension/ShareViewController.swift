import UIKit
import MLKitTranslate
import MLKitLanguageID

final class ShareViewController: UIViewController {

    private let card = UIView()
    private let titleLabel = UILabel()
    private let bodyLabel = UILabel()
    private let closeButton = UIButton(type: .system)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        setupCard()
        extractIncomingText { [weak self] text in
            guard let self = self else { return }
            guard let text = text, !text.isEmpty else {
                self.setCard(title: "Nothing to translate", body: "Share a plain text message and try again.")
                return
            }
            self.setCard(title: "Translating\u{2026}", body: "")
            Self.detectAndTranslate(text) { [weak self] result in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if let result = result {
                        self.setCard(title: "English", body: result)
                    } else {
                        self.setCard(
                            title: "Translation unavailable",
                            body: "Could not detect a non-English language or the offline model is not downloaded yet."
                        )
                    }
                }
            }
        }
    }

    static func detectAndTranslate(_ text: String, completion: @escaping (String?) -> Void) {
        let identifier = NaturalLanguage.languageIdentification(
            options: LanguageIdentifierOptions(confidenceThreshold: 0.45)
        )
        identifier.identifyLanguage(forText: text) { tag, _ in
            identifier.close()
            guard let tag = tag,
                  tag != "und",
                  tag != "en",
                  let source = TranslateLanguage.forLanguageCode(tag) else {
                completion(nil)
                return
            }
            let options = TranslatorOptions(sourceLanguage: source, targetLanguage: .english)
            let translator = NaturalLanguage.translator(options: options)
            translator.downloadModelIfNeeded { error in
                if error != nil {
                    translator.close()
                    completion(nil)
                    return
                }
                translator.translate(text) { translated, translateError in
                    translator.close()
                    completion(translateError == nil ? translated : nil)
                }
            }
        }
    }

    // MARK: - Input extraction

    private func extractIncomingText(_ done: @escaping (String?) -> Void) {
        guard let items = extensionContext?.inputItems as? [NSExtensionItem] else {
            done(nil)
            return
        }
        for item in items {
            for provider in item.attachments ?? [] where provider.hasItemConformingToTypeIdentifier("public.plain-text") {
                provider.loadItem(forTypeIdentifier: "public.plain-text", options: nil) { raw, _ in
                    let text: String?
                    switch raw {
                    case let string as String:
                        text = string
                    case let data as Data:
                        text = String(data: data, encoding: .utf8)
                    case let url as URL:
                        text = try? String(contentsOf: url, encoding: .utf8)
                    default:
                        text = nil
                    }
                    DispatchQueue.main.async { done(text) }
                    return
                }
                return
            }
        }
        done(nil)
    }

    // MARK: - Bubble card UI

    private func setupCard() {
        card.backgroundColor = UIColor(red: 0.125, green: 0.129, blue: 0.141, alpha: 0.97)
        card.layer.cornerRadius = 20
        card.layer.masksToBounds = true
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        titleLabel.font = .boldSystemFont(ofSize: 13)
        titleLabel.textColor = UIColor.white.withAlphaComponent(0.6)
        titleLabel.numberOfLines = 1

        bodyLabel.font = .systemFont(ofSize: 17)
        bodyLabel.textColor = .white
        bodyLabel.numberOfLines = 0

        closeButton.setTitle("Close", for: .normal)
        closeButton.tintColor = UIColor(red: 0.31, green: 0.43, blue: 0.97, alpha: 1)
        closeButton.addAction(UIAction { [weak self] _ in self?.finish() }, for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [titleLabel, bodyLabel, closeButton])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)

        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            card.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            card.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -32),

            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -18),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -14),

            closeButton.heightAnchor.constraint(equalToConstant: 34),
            closeButton.contentHorizontalAlignment = .center,

            card.heightAnchor.constraint(greaterThanOrEqualToConstant: 150)
        ])
    }

    private func setCard(title: String, body: String) {
        titleLabel.text = title
        bodyLabel.text = body.isEmpty ? " " : body
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
