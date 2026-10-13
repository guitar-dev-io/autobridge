import UIKit

// The building blocks of the drawn CarPlay screen (`CarScreenViewController`): its colours, a
// focus that moves without touches, and the tile grid every page is made of.

/// The car home design system, the same values as the Android `HomeDashboardTheme` ("Home v2"),
/// so the two car screens read as one app.
enum CarStyle {
    static let background = UIColor(hex: 0x121418)
    static let surface = UIColor(hex: 0x1C1F25)
    static let raised = UIColor(hex: 0x242831)
    static let pressed = UIColor(hex: 0x2A2F38)
    static let control = UIColor(hex: 0x2C3036)
    static let text = UIColor(hex: 0xF1F3F5)
    static let secondary = UIColor(hex: 0x9AA1AA)
    static let section = UIColor(hex: 0xC7CCD2)
    /// AutoBridge blue: the play button, the focus border.
    static let accent = UIColor(hex: 0xB3C7F5)
    static let onAccent = UIColor(hex: 0x142540)
    /// The "Bridge" half of the wordmark.
    static let wordmark = UIColor(hex: 0x7FA9F5)
    static let focus = accent

    static func font(_ size: CGFloat, _ weight: UIFont.Weight = .regular) -> UIFont {
        .systemFont(ofSize: size, weight: weight)
    }

    /// A linear mix of two colours; [t] = 0 is [from]. Icon chips are the card colour with a little
    /// of their accent mixed in, as on Android.
    static func mix(_ from: UIColor, _ to: UIColor, _ t: CGFloat) -> UIColor {
        var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        from.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        to.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(red: r1 + (r2 - r1) * t, green: g1 + (g2 - g1) * t, blue: b1 + (b2 - b1) * t, alpha: 1)
    }
}

// MARK: - Focus

/// Where a move goes.
enum CarDirection { case up, down, left, right }

/// A highlight that moves between the screen's controls in a direction and presses the one it is
/// on. It is how the screen is driven when the window gets no touches: CarPlay's map buttons, a
/// pan on the screen and a rotary knob all end up here. With touches, a tap works as usual and the
/// highlight simply follows it.
@MainActor
final class CarFocus {
    private weak var root: UIView?
    private(set) weak var focused: UIControl?

    init(root: UIView) {
        self.root = root
    }

    /// Moves to the nearest control in [direction]; with nothing focused, to the first one.
    func move(_ direction: CarDirection) {
        let all = candidates()
        guard let from = focused, all.contains(where: { $0 === from }), let root else {
            focus(all.first)
            return
        }
        let origin = from.convert(CGPoint(x: from.bounds.midX, y: from.bounds.midY), to: root)
        var best: (control: UIControl, score: CGFloat)?
        for control in all where control !== from {
            let center = control.convert(CGPoint(x: control.bounds.midX, y: control.bounds.midY), to: root)
            let dx = center.x - origin.x
            let dy = center.y - origin.y
            let (along, across): (CGFloat, CGFloat)
            switch direction {
            case .up: (along, across) = (-dy, abs(dx))
            case .down: (along, across) = (dy, abs(dx))
            case .left: (along, across) = (-dx, abs(dy))
            case .right: (along, across) = (dx, abs(dy))
            }
            guard along > 4 else { continue }
            // Straight ahead beats a closer control off to the side, and anything inside a 45° cone
            // beats anything outside it, so "right" never jumps up a row to a nearer tab.
            let score = along + across * 3 + (across > along ? 100_000 : 0)
            if best == nil || score < best!.score { best = (control, score) }
        }
        if let best { focus(best.control) }
    }

    /// Highlights the first control inside [container], for a page that just opened.
    func focusFirst(in container: UIView?) {
        guard let container else { return }
        focus(candidates().first { $0.isDescendant(of: container) })
    }

    /// Presses the focused control, as a tap would.
    func activate() {
        guard let focused, isUsable(focused) else {
            move(.down)
            return
        }
        focused.sendActions(for: .touchUpInside)
    }

    func focus(_ control: UIControl?) {
        if let old = focused, old !== control { Self.setRing(old, on: false) }
        focused = control
        guard let control else { return }
        Self.setRing(control, on: true)
        scrollIntoView(control)
    }

    /// Takes the highlight off without moving it anywhere, for a screen that was replaced.
    func reset() {
        if let focused { Self.setRing(focused, on: false) }
        focused = nil
    }

    static func setRing(_ control: UIControl, on: Bool) {
        control.layer.borderColor = CarStyle.focus.cgColor
        control.layer.borderWidth = on ? 3 : 0
    }

    private func scrollIntoView(_ control: UIControl) {
        var view = control.superview
        while let current = view {
            if let scroll = current as? UIScrollView, scroll.isScrollEnabled {
                let rect = control.convert(control.bounds, to: scroll).insetBy(dx: 0, dy: -16)
                scroll.scrollRectToVisible(rect, animated: true)
                return
            }
            view = current.superview
        }
    }

    /// Every visible, enabled control under the root, top to bottom, left to right. Controls in a
    /// scroll view that are laid out but scrolled away still count, so a move can bring them in.
    private func candidates() -> [UIControl] {
        guard let root else { return [] }
        var found: [UIControl] = []
        func walk(_ view: UIView) {
            if view.isHidden || view.alpha < 0.01 { return }
            if let control = view as? UIControl, isUsable(control), !(control is UITextField) {
                found.append(control)
                return
            }
            for child in view.subviews { walk(child) }
        }
        walk(root)
        return found.sorted { a, b in
            let pa = a.convert(CGPoint.zero, to: root)
            let pb = b.convert(CGPoint.zero, to: root)
            return abs(pa.y - pb.y) > 8 ? pa.y < pb.y : pa.x < pb.x
        }
    }

    private func isUsable(_ control: UIControl) -> Bool {
        control.isEnabled && control.window != nil && !control.isHidden && control.bounds.width > 1
    }
}

// MARK: - Tiles

/// One thing on a page: a channel, a source, a category, a site, a shortcut.
struct CarTile {
    var title: String
    var subtitle: String = ""
    /// A logo to fetch through `ImageLoader`.
    var logo: String = ""
    /// A symbol shown when there is no logo (or until it arrives).
    var symbol: String = "tv"
    var tint: UIColor = CarStyle.accent
    var action: () -> Void
}

struct CarTileSection {
    var header: String?
    var tiles: [CarTile]
}

/// A pressable tile, in one of three shapes from the Android car home:
/// - `tile`: a channel logo over its name, for the channel grids;
/// - `row`: a small icon chip beside a title and a line under it (sources, categories, recents);
/// - `card`: the quick-access card, a large icon chip beside a big label.
///
/// It is a `UIControl` so the focus can land on it and press it like any button.
final class CarTileButton: UIControl {
    enum Shape { case tile, row, card }

    let imageView = UIImageView()
    let titleLabel = UILabel()
    let subtitleLabel = UILabel()
    var onTap: (() -> Void)?
    /// The rounded square the icon sits in, tinted with the tile's accent.
    private let chip = UIView()
    private let stack = UIStackView()
    private let labels = UIStackView()
    private var sizing: [NSLayoutConstraint] = []
    private var loading: Task<Void, Never>?
    private var logo = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = CarStyle.surface
        layer.cornerCurve = .continuous
        clipsToBounds = true

        imageView.contentMode = .scaleAspectFit
        imageView.translatesAutoresizingMaskIntoConstraints = false
        chip.layer.cornerCurve = .continuous
        chip.layer.borderWidth = 1
        chip.clipsToBounds = true
        chip.addSubview(imageView)
        titleLabel.textColor = CarStyle.text
        subtitleLabel.font = CarStyle.font(13)
        subtitleLabel.textColor = CarStyle.secondary
        labels.axis = .vertical
        labels.spacing = 2
        labels.addArrangedSubview(titleLabel)
        labels.addArrangedSubview(subtitleLabel)
        stack.addArrangedSubview(chip)
        stack.addArrangedSubview(labels)
        stack.isUserInteractionEnabled = false
        titleLabel.setContentCompressionResistancePriority(.required, for: .vertical)
        labels.setContentHuggingPriority(.required, for: .vertical)
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
            stack.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: 8),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -8),
        ])
        addTarget(self, action: #selector(tapped), for: .touchUpInside)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var isHighlighted: Bool {
        didSet { backgroundColor = isHighlighted ? CarStyle.pressed : CarStyle.surface }
    }

    @objc private func tapped() {
        onTap?()
    }

    func configure(_ tile: CarTile, shape: Shape) {
        onTap = tile.action
        titleLabel.text = tile.title
        subtitleLabel.text = tile.subtitle
        subtitleLabel.isHidden = tile.subtitle.isEmpty
        imageView.tintColor = tile.tint
        chip.backgroundColor = CarStyle.mix(CarStyle.surface, tile.tint, 0.08)
        chip.layer.borderColor = CarStyle.mix(CarStyle.surface, tile.tint, 0.30).cgColor

        NSLayoutConstraint.deactivate(sizing)
        let chipSize: CGFloat
        let inset: CGFloat
        switch shape {
        case .tile:
            layer.cornerRadius = 18
            stack.axis = .vertical
            stack.alignment = .fill
            stack.spacing = 6
            titleLabel.textAlignment = .center
            subtitleLabel.textAlignment = .center
            titleLabel.numberOfLines = 2
            titleLabel.font = CarStyle.font(14, .semibold)
            chip.layer.cornerRadius = 0
            chip.layer.borderWidth = 0
            chip.backgroundColor = .clear
            // A fixed logo height, so a tall logo can never push the name out of the tile.
            sizing = [
                chip.heightAnchor.constraint(equalToConstant: 52),
                imageView.leadingAnchor.constraint(equalTo: chip.leadingAnchor),
                imageView.trailingAnchor.constraint(equalTo: chip.trailingAnchor),
                imageView.topAnchor.constraint(equalTo: chip.topAnchor),
                imageView.bottomAnchor.constraint(equalTo: chip.bottomAnchor),
                stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
                stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            ]
            NSLayoutConstraint.activate(sizing)
            setLogo(tile.logo, symbol: tile.symbol)
            return
        case .row:
            layer.cornerRadius = 18
            titleLabel.font = CarStyle.font(16, .semibold)
            chipSize = 44
            inset = 12
            chip.layer.cornerRadius = 13
        case .card:
            layer.cornerRadius = 22
            titleLabel.font = CarStyle.font(20, .semibold)
            chipSize = 56
            inset = 20
            chip.layer.cornerRadius = 16
        }
        chip.layer.borderWidth = 1
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 14
        titleLabel.textAlignment = .natural
        subtitleLabel.textAlignment = .natural
        titleLabel.numberOfLines = 1
        // A long name ("YouTube Music") shrinks a little before it is cut.
        titleLabel.adjustsFontSizeToFitWidth = true
        titleLabel.minimumScaleFactor = 0.75
        let pad = tile.logo.isEmpty ? chipSize * 0.24 : 4
        sizing = [
            chip.widthAnchor.constraint(equalToConstant: chipSize),
            chip.heightAnchor.constraint(equalToConstant: chipSize),
            imageView.leadingAnchor.constraint(equalTo: chip.leadingAnchor, constant: pad),
            imageView.trailingAnchor.constraint(equalTo: chip.trailingAnchor, constant: -pad),
            imageView.topAnchor.constraint(equalTo: chip.topAnchor, constant: pad),
            imageView.bottomAnchor.constraint(equalTo: chip.bottomAnchor, constant: -pad),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: inset),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -inset),
        ]
        NSLayoutConstraint.activate(sizing)
        setLogo(tile.logo, symbol: tile.symbol)
    }

    private func setLogo(_ url: String, symbol: String) {
        loading?.cancel()
        logo = url
        let placeholder = UIImage(systemName: symbol)
        if url.isEmpty {
            imageView.image = placeholder
            return
        }
        if let ready = ImageLoader.shared.cached(url) {
            imageView.image = ready
            return
        }
        imageView.image = placeholder
        loading = Task { [weak self] in
            let image = await ImageLoader.shared.load(url)
            guard let self, !Task.isCancelled, self.logo == url, let image else { return }
            self.imageView.image = image
        }
    }

    func prepareForReuse() {
        loading?.cancel()
        onTap = nil
        CarFocus.setRing(self, on: false)
    }
}

private final class CarTileCell: UICollectionViewCell {
    static let id = "tile"
    let button = CarTileButton()

    override init(frame: CGRect) {
        super.init(frame: frame)
        button.frame = contentView.bounds
        button.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentView.addSubview(button)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func prepareForReuse() {
        super.prepareForReuse()
        button.prepareForReuse()
    }
}

private final class CarHeaderView: UICollectionReusableView {
    static let id = "header"
    let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.font = CarStyle.font(17, .semibold)
        label.textColor = CarStyle.section
        label.frame = bounds.inset(by: UIEdgeInsets(top: 0, left: 4, bottom: 0, right: 4))
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(label)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }
}

/// A scrolling grid of tiles in sections, sized for a car screen: channel tiles about 150 points
/// wide, or wide rows two to a line.
final class CarGridView: UIView, UICollectionViewDataSource {
    private let shape: CarTileButton.Shape
    private var sections: [CarTileSection] = []
    private let empty = UILabel()
    private(set) lazy var collection = UICollectionView(frame: .zero, collectionViewLayout: makeLayout())

    init(shape: CarTileButton.Shape) {
        self.shape = shape
        super.init(frame: .zero)
        collection.backgroundColor = .clear
        collection.dataSource = self
        collection.register(CarTileCell.self, forCellWithReuseIdentifier: CarTileCell.id)
        collection.register(
            CarHeaderView.self,
            forSupplementaryViewOfKind: UICollectionView.elementKindSectionHeader,
            withReuseIdentifier: CarHeaderView.id
        )
        collection.frame = bounds
        collection.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(collection)

        empty.font = CarStyle.font(17)
        empty.textColor = CarStyle.secondary
        empty.textAlignment = .center
        empty.numberOfLines = 0
        empty.translatesAutoresizingMaskIntoConstraints = false
        addSubview(empty)
        NSLayoutConstraint.activate([
            empty.centerXAnchor.constraint(equalTo: centerXAnchor),
            empty.centerYAnchor.constraint(equalTo: centerYAnchor),
            empty.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, multiplier: 0.8),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    /// Replaces what the grid shows; [emptyText] is said when there is nothing.
    func show(_ sections: [CarTileSection], emptyText: String = "") {
        self.sections = sections.filter { !$0.tiles.isEmpty }
        empty.text = emptyText
        empty.isHidden = !self.sections.isEmpty
        collection.reloadData()
    }

    private func makeLayout() -> UICollectionViewLayout {
        let shape = self.shape
        return UICollectionViewCompositionalLayout { [weak self] index, environment in
            let width = environment.container.effectiveContentSize.width
            let columns: Int
            let height: CGFloat
            switch shape {
            case .tile:
                columns = max(2, Int(width / 150))
                height = 120
            case .row:
                columns = width > 520 ? 2 : 1
                height = 68
            case .card:
                columns = width > 520 ? 3 : 2
                height = 104
            }
            let item = NSCollectionLayoutItem(layoutSize: .init(
                widthDimension: .fractionalWidth(1 / CGFloat(columns)),
                heightDimension: .fractionalHeight(1)
            ))
            item.contentInsets = NSDirectionalEdgeInsets(top: 5, leading: 5, bottom: 5, trailing: 5)
            let group = NSCollectionLayoutGroup.horizontal(
                layoutSize: .init(widthDimension: .fractionalWidth(1), heightDimension: .absolute(height)),
                subitem: item,
                count: columns
            )
            let section = NSCollectionLayoutSection(group: group)
            section.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 11, bottom: 12, trailing: 11)
            if let self, self.sections.indices.contains(index), self.sections[index].header != nil {
                section.boundarySupplementaryItems = [NSCollectionLayoutBoundarySupplementaryItem(
                    layoutSize: .init(widthDimension: .fractionalWidth(1), heightDimension: .absolute(36)),
                    elementKind: UICollectionView.elementKindSectionHeader,
                    alignment: .top
                )]
            }
            return section
        }
    }

    func numberOfSections(in collectionView: UICollectionView) -> Int { sections.count }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        sections[section].tiles.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: CarTileCell.id, for: indexPath)
        if let cell = cell as? CarTileCell {
            cell.button.configure(sections[indexPath.section].tiles[indexPath.item], shape: shape)
        }
        return cell
    }

    func collectionView(
        _ collectionView: UICollectionView,
        viewForSupplementaryElementOfKind kind: String,
        at indexPath: IndexPath
    ) -> UICollectionReusableView {
        let view = collectionView.dequeueReusableSupplementaryView(
            ofKind: kind,
            withReuseIdentifier: CarHeaderView.id,
            for: indexPath
        )
        (view as? CarHeaderView)?.label.text = sections[indexPath.section].header
        return view
    }
}

/// A round icon button for toolbars and the player controls.
func carIconButton(_ symbol: String, size: CGFloat = 44, action: @escaping () -> Void) -> UIButton {
    let button = UIButton(type: .system)
    button.setImage(
        UIImage(systemName: symbol, withConfiguration: UIImage.SymbolConfiguration(pointSize: size * 0.42, weight: .semibold)),
        for: .normal
    )
    button.tintColor = CarStyle.text
    button.backgroundColor = CarStyle.control
    button.layer.cornerRadius = size / 2
    button.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
        button.widthAnchor.constraint(equalToConstant: size),
        button.heightAnchor.constraint(equalToConstant: size),
    ])
    button.addAction(UIAction { _ in action() }, for: .touchUpInside)
    return button
}
