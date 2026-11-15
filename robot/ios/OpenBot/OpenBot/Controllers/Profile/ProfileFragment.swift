//
// Created by Nitish Yadav on 06/04/23.
//

import Foundation
import UIKit

/**
 Class for profile fragment extending ViewController.

 The profile lives on this phone only (see `LocalProfile` in EditProfileFragment.swift):
 there is no account system, so the page simply shows the edit-profile row.
 */
class profileFragment: UIViewController {
    var editProfileLabel = UILabel()
    var userImgView = UIImageView()
    var signInView = UIView()

    /**
     Calls after view of profile will load. This will create UI of the View
     */
    override func viewDidLoad() {
        super.viewDidLoad()
        tabBarItem.title = Strings.profileTab
        createMyProfileLabel();
        navigationController?.navigationBar.setBackgroundImage(UIImage(), for: .default)
        navigationController?.navigationBar.shadowImage = UIImage()
        signInView = CustomView(frame: currentOrientation == .portrait ? CGRect(origin: CGPoint(x: 0, y: 150), size: CGSize(width: width, height: height - 150)) :
                CGRect(origin: CGPoint(x: 0, y: 100), size: CGSize(width: height, height: width - 100))
                , backgroundColor: traitCollection.userInterfaceStyle == .dark ? UIColor.black : UIColor.white);
        view.addSubview(signInView);
        createEditProfileLabel();
        createEditProfileIcons();
    }

    /**
     Override function calls when orientation of phone changes
     - Parameters:
       - size:
       - coordinator:
     */
    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        if currentOrientation == .portrait {
            signInView.frame = CGRect(origin: CGPoint(x: 0, y: 140), size: CGSize(width: width, height: height - 140))
            userImgView.frame.origin.x = safeAreaLayoutValue.left + 38;
            editProfileLabel.frame.origin.x = userImgView.right + 20;
        } else {
            signInView.frame = CGRect(origin: CGPoint(x: 0, y: 100), size: CGSize(width: height, height: width - 100))
            userImgView.frame.origin.x = safeAreaLayoutValue.top + 38;
            editProfileLabel.frame.origin.x = userImgView.right + 20;
        }
    }

    /**
     Function to create My profile Label on top
     */
    private func createMyProfileLabel() {
        let label = CustomLabel(text: Strings.myProfileHeading, fontSize: 15, fontColor: Colors.textColor ?? .black, frame: CGRect(origin: .zero, size: CGSize(width: 200, height: 40)));
        label.font = HelveticaNeue.regular(size: 15);
        label.translatesAutoresizingMaskIntoConstraints = false;
        view.addSubview(label)
        label.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20).isActive = true;
        label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 10).isActive = true;
        label.layoutIfNeeded();
    }

    /**
     Function to create the label for edit profile
     */
    private func createEditProfileLabel() {
        editProfileLabel = CustomLabel(text: Strings.editProfile, fontSize: 16, fontColor: Colors.textColor ?? .black, frame: CGRect(x: userImgView.right + 72, y: 0, width: 120, height: 50))
        signInView.addSubview(editProfileLabel);
        editProfileLabel.isUserInteractionEnabled = true;
        let tapOnEditProfile = UITapGestureRecognizer(target: self, action: #selector(editProfileHandler))
        editProfileLabel.addGestureRecognizer(tapOnEditProfile);
    }

    /**
     Function to create the edit profile icon
     */
    private func createEditProfileIcons() {
        let userIcon = UIImage(named: "user_icon");
        userImgView = UIImageView(frame: CGRect(x: safeAreaLayoutValue.left + 38, y: 17, width: userIcon?.size.width ?? 18, height: userIcon?.size.height ?? 18));
        userImgView.image = userIcon;
        signInView.addSubview(userImgView);
    }

    /**
     Function that will add edit profile view controller to navigation controller
     */
    @objc func editProfileHandler() {
        let storyboard = UIStoryboard(name: "openCode", bundle: nil)
        let viewController = storyboard.instantiateViewController(withIdentifier: "editProfile")
        self.navigationController?.pushViewController(viewController, animated: true)
    }
}
