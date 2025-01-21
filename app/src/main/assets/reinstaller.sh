#!/bin/bash

# Check if running as root
if [[ $(id -u) -ne 0 ]]; then
  echo "Error: This script must be run as root (su)."
  exit 1
fi

# Get the current user ID
CURRENT_USER=$(am get-current-user)

# Check if user ID retrieval was successful
if [[ -z "$CURRENT_USER" ]]; then
  echo "Error: Could not determine current user ID."
  exit 1
fi

echo "Current user ID: $CURRENT_USER"

# Iterate through third-party packages
pm list packages -3 -i | while read -r line; do
  # Extract package name and installer
  package=$(echo "$line" | cut -d ' ' -f 1 | cut -d ':' -f 2)
  installer=$(echo "$line" | cut -d ' ' -f 3 | cut -d '=' -f 2)

  # Handle null installer values
  if [[ -z "$installer" || "$installer" == "null" ]]; then
    installer="N/A"
  fi

  # Check if the installer is not com.android.vending or N/A
  if [[ "$installer" != "com.android.vending" ]]; then
    echo "----------------------------------------"
    echo "Package: $package"
    echo "Installer: $installer"

    # Get the path to the base.apk
    apk_path=$(pm path "$package" | cut -d ':' -f 2)

    # Check if the apk_path is valid
    if [[ -z "$apk_path" || ! -f "$apk_path" ]]; then
      echo "Error: Could not find base.apk for $package"
      continue  # Skip to the next package
    fi

    # Reinstall the app, preserving data and setting the installer
    echo "Reinstalling $package and setting installer to Play Store for user $CURRENT_USER..."
    pm install -r -d -i "com.android.vending" --user $CURRENT_USER --install-reason 0 "$apk_path" &>/dev/null

    # Check if the reinstall was successful
    if [[ $? -eq 0 ]]; then
      # Verify the new installer
      new_installer=$(pm list packages -i "$package" | cut -d ' ' -f 3 | cut -d '=' -f 2)

      if [[ "$new_installer" == "com.android.vending" ]]; then
        echo "Successfully set installer for $package to Play Store"
      else
        echo "Error: Failed to set installer for $package (Installer: $new_installer)"
      fi
    else
      echo "Error: Failed to reinstall $package"
    fi
  fi
done

echo "----------------------------------------"
echo "Process completed."
echo "all credits to @T3SL4"
echo "used by Thor Aka Thomas Shelby"
echo "----------------------------------------"
