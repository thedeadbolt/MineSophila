# End-to-end tests

Full product runs, e.g. honey-factory scene → adapter → fly-vision → detection.
Mark every test `@pytest.mark.e2e`. If a test needs a live Minecraft instance, also mark
it `@pytest.mark.minecraft`. Those tests are deselected by default and run with
`just test-minecraft`.

Empty until the adapter exists.
