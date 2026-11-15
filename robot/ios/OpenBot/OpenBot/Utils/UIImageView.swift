//
// Created by Nitish Yadav on 11/04/23.
//

import Foundation
import UIKit

extension UIImageView {
    func load(url: URL, activityIndicatorStyle: UIActivityIndicatorView.Style = .gray) {
        // Create and start the activity indicator
        let activityIndicator = UIActivityIndicatorView(style: activityIndicatorStyle)
        activityIndicator.translatesAutoresizingMaskIntoConstraints = false
        addSubview(activityIndicator)
        activityIndicator.centerXAnchor.constraint(equalTo: centerXAnchor).isActive = true
        activityIndicator.centerYAnchor.constraint(equalTo: centerYAnchor).isActive = true
        activityIndicator.startAnimating()

        DispatchQueue.global().async { [weak self] in
            let data = try? Data(contentsOf: url)
            let image = data.flatMap { UIImage(data: $0) }
            DispatchQueue.main.async {
                if let image = image {
                    self?.image = image
                }
                // Hide the activity indicator once the image is loaded (or failed)
                activityIndicator.stopAnimating()
                activityIndicator.removeFromSuperview()
            }
        }
    }
}
