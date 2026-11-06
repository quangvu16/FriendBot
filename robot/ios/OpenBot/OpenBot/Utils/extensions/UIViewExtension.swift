//
// Created by Nitish Yadav on 13/09/22.
//

import UIKit

/// This extension adds a method to UIView to update any "adapted constraints" on the view.
extension UIView {
    func updateAdaptedConstraints() {
        let adaptedConstraints = constraints.filter { (constraint) -> Bool in
            constraint is AdaptedConstraint
        } as! [AdaptedConstraint]

        for constraint in adaptedConstraints {
            constraint.resetConstant()
            constraint.awakeFromNib()
        }
    }

}

/// This extension adds computed properties to UIView to get/set the top, bottom, right, and left edges of the view's frame.
extension UIView {

    var top: CGFloat {
        get {
            self.frame.origin.y
        }
        set {
            var frame = self.frame
            frame.origin.y = newValue
            self.frame = frame
        }
    }

    var bottom: CGFloat {
        get {
            frame.origin.y + frame.size.height
        }
        set {
            var frame = self.frame
            frame.origin.y = newValue - self.frame.size.height
            self.frame = frame
        }
    }

    var right: CGFloat {
        get {
            self.frame.origin.x + self.frame.size.width
        }
        set {
            var frame = self.frame
            frame.origin.x = newValue - self.frame.size.width
            self.frame = frame
        }
    }

    var left: CGFloat {
        get {
            self.frame.origin.x
        }
        set {
            var frame = self.frame
            frame.origin.x = newValue
            self.frame = frame
        }
    }
}

class BottomSheetView: UIView {

    override init(frame: CGRect) {
        let newFrame = currentOrientation == .portrait ? CGRect(x: safeAreaLayoutValue.left, y: height - 250 , width: width - safeAreaLayoutValue.left, height: 250) :
                currentOrientation == .landscapeLeft ? CGRect(x: safeAreaLayoutValue.top, y: width - 250, width: height - safeAreaLayoutValue.top-safeAreaLayoutValue.bottom, height: 250) :
                CGRect(x: safeAreaLayoutValue.bottom, y: width - 250, width: height - safeAreaLayoutValue.top - safeAreaLayoutValue.bottom, height: 250);
        super.init(frame: newFrame)
        setup()
        NotificationCenter.default.addObserver(self, selector: #selector(orientationDidChange), name: UIDevice.orientationDidChangeNotification, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self, name: UIDevice.orientationDidChangeNotification, object: nil)
    }


    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }


    private func setup() {
        backgroundColor = Colors.bdColor;
        layer.cornerRadius = 30;
        layer.maskedCorners = [.layerMaxXMinYCorner, .layerMinXMinYCorner]
    }


    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection != nil && previousTraitCollection!.verticalSizeClass != traitCollection.verticalSizeClass {
            if traitCollection.verticalSizeClass == .compact {
                frame = currentOrientation == .landscapeLeft ? CGRect(x: safeAreaLayoutValue.top, y: width - 250, width: height - safeAreaLayoutValue.top - safeAreaLayoutValue.bottom, height: 250) :
                        CGRect(x: safeAreaLayoutValue.bottom, y: width - 250, width: height - safeAreaLayoutValue.top - safeAreaLayoutValue.bottom, height: 250);
            } else {
                frame = CGRect(x: safeAreaLayoutValue.left, y: height - 250, width: width - safeAreaLayoutValue.left, height: 250)
            }
        }
    }

    var orientationDidChanged: UIDeviceOrientation = .portrait {
        didSet {
            if oldValue == .landscapeLeft && orientationDidChanged == .landscapeRight {
                // The device orientation has changed from landscape left to landscape right
                // Do something here
                frame = CGRect(x: safeAreaLayoutValue.bottom, y: width - 250, width: height - safeAreaLayoutValue.top - safeAreaLayoutValue.bottom, height: 250)
            }
            if oldValue == .landscapeRight && orientationDidChanged == .landscapeLeft {
                // The device orientation has changed from landscape right to landscape left
                frame = CGRect(x: safeAreaLayoutValue.top, y: width - 250, width: height - safeAreaLayoutValue.top, height: 250)

            }
        }
    }


    @objc func orientationDidChange() {
        switch UIDevice.current.orientation {
        case .portrait, .portraitUpsideDown, .landscapeLeft, .landscapeRight:
            orientationDidChanged = UIDevice.current.orientation
        default:
            break
        }
    }


}

class CustomView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
    }

    required init?(coder aDecoder: NSCoder) {
        super.init(coder: aDecoder)
    }

    convenience init(frame: CGRect, backgroundColor: UIColor) {
        self.init(frame: frame);
        setup(backgroundColor: backgroundColor)
    }


    private func setup(backgroundColor: UIColor) {
        self.backgroundColor = backgroundColor;
    }
}

