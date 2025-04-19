# IntelliJ Bookmarks Learnings

## Important Notes

1. **Avoid `com.intellij.ide.bookmarks.BookmarkManager`**
   - This class is deprecated and no longer works properly
   - Use `com.intellij.ide.bookmark.BookmarksManager` instead
   - Get instance via `BookmarksManager.getInstance(project)`

2. **Bookmark Description**
   - `toString()` on a bookmark does NOT contain the description
   - To get a bookmark's description:
     ```kotlin
     val description = bookmarkGroup.getDescription(bookmark)
     ```
   - Description is stored in the bookmark group, not the bookmark itself

3. **Bookmark Groups**
   - Each bookmark belongs to a group
   - Groups can be created with `bookmarksManager.addGroup(name, isDefault)`
   - Groups can be retrieved with `bookmarksManager.groups`
   - Empty groups should be cleaned up to avoid clutter

4. **Bookmark State**
   - Bookmark state contains:
     - Provider (e.g. "com.intellij.ide.bookmark.providers.LineBookmarkProvider")
     - Attributes (file path, URL, line number, description)
   - Create new bookmarks with `bookmarksManager.createBookmark(state)`

## Best Practices

- Always check if a bookmark exists in a group before adding
- Clean up empty bookmark groups
- Use proper error handling when working with bookmarks
- Store bookmarks in appropriate groups for better organization 