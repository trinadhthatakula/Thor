# suCore

This module is based on the `core` module from the [libsu](https://github.com/topjohnwu/libsu) open-source library by [topjohnwu](https://github.com/topjohnwu).  
Original project: https://github.com/topjohnwu/libsu

## Credits

- The original implementation and design are credited to the [libsu](https://github.com/topjohnwu/libsu) project and its contributors.

## Changes Made

- Entire codebase has been converted from Java to Kotlin for improved readability and maintainability.
- Removed usage of Android `Context` as a static field to prevent potential memory leaks.
- Refactored code to follow Kotlin best practices and idioms.

## ⚠️ Caution

The changes made here are specifically to support the Thor project and may not be compatible with other use cases. please use the original [libsu](https://github.com/topjohnwu/libsu) project for all your purposes.

## License

- The original [libsu](https://github.com/topjohnwu/libsu) project is licensed under the Apache License 2.0. All modifications and usage in this module comply with the Apache-2.0 requirements.
- This module, as part of the Thor project, is distributed under the GNU General Public License v3.0 (GPL-3.0).
- See the [LICENSE](../LICENSE) file for the full license text.