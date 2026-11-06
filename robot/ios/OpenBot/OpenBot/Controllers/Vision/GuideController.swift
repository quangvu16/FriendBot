//
//  GuideController.swift
//  OpenBot
//
//  Instruction pages adapted from Kirstin813/L4-Individual-Project: one scrollable screen per
//  vision mode (what it does, materials, steps, tips). Mirrors the Android GuideFragment.
//

import UIKit

final class GuideController: UIViewController {

    static let guideColour = "colour"
    static let guideFace = "face"
    static let guideLine = "line"
    static let guideAvoidance = "avoidance"

    private let guideId: String

    init(guideId: String) {
        self.guideId = guideId
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        navigationItem.title = Strings.tileGuides

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -32),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -20)
        ])

        let intro = L10n.string("guide_\(guideId)_intro")
        stack.addArrangedSubview(
            makeLabel(intro, size: 15, bold: false, color: .label))
        stack.addArrangedSubview(makeSpacer(8))

        stack.addArrangedSubview(
            makeLabel(L10n.string("guide_section_materials"), size: 17, bold: true, color: .label))
        for item in lines("guide_\(guideId)_materials") {
            stack.addArrangedSubview(makeLabel("•  " + item, size: 15, bold: false, color: .label))
        }
        stack.addArrangedSubview(makeSpacer(8))

        stack.addArrangedSubview(
            makeLabel(L10n.string("guide_section_steps"), size: 17, bold: true, color: .label))
        for (i, item) in lines("guide_\(guideId)_steps").enumerated() {
            stack.addArrangedSubview(makeLabel("\(i + 1).  " + item, size: 15, bold: false, color: .label))
        }
        stack.addArrangedSubview(makeSpacer(8))

        stack.addArrangedSubview(
            makeLabel(L10n.string("guide_section_tips"), size: 17, bold: true, color: .label))
        for item in lines("guide_\(guideId)_tips") {
            stack.addArrangedSubview(makeLabel("•  " + item, size: 15, bold: false, color: .label))
        }
        stack.addArrangedSubview(makeSpacer(12))

        let credit = makeLabel(L10n.string("guide_credit"), size: 12, bold: false, color: .secondaryLabel)
        credit.font = UIFont.italicSystemFont(ofSize: 12)
        stack.addArrangedSubview(credit)
    }

    /// Guide lists are stored newline-separated in Localizable.strings (mirrors the
    /// Android string-arrays).
    private func lines(_ key: String) -> [String] {
        L10n.string(key)
            .components(separatedBy: "\n")
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private func makeLabel(_ text: String, size: CGFloat, bold: Bool, color: UIColor) -> UILabel {
        let label = UILabel()
        label.text = text
        label.numberOfLines = 0
        label.textColor = color
        label.font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
        return label
    }

    private func makeSpacer(_ height: CGFloat) -> UIView {
        let view = UIView()
        view.heightAnchor.constraint(equalToConstant: height).isActive = true
        return view
    }
}
