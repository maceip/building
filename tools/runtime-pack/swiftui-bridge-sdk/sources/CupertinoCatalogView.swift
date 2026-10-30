/// Distinct visual surfaces and explicit bridge adapters. Overloads share a case;
/// typed value/state models describe their differences. UIKit-only APIs are separate.
public enum CupertinoSurfaceName: String, CaseIterable, Sendable {
    case adaptiveAlertDialog = "AdaptiveAlertDialog"
    case adaptiveAlertDialogNative = "AdaptiveAlertDialogNative"
    case adaptiveButton = "AdaptiveButton"
    case adaptiveCheckbox = "AdaptiveCheckbox"
    case adaptiveCircularProgressIndicator = "AdaptiveCircularProgressIndicator"
    case adaptiveDatePicker = "AdaptiveDatePicker"
    case adaptiveDivider = "AdaptiveDivider"
    case adaptiveFilledIconButton = "AdaptiveFilledIconButton"
    case adaptiveHorizontalDivider = "AdaptiveHorizontalDivider"
    case adaptiveIconButton = "AdaptiveIconButton"
    case adaptiveNavigationBar = "AdaptiveNavigationBar"
    case adaptiveRangeSlider = "AdaptiveRangeSlider"
    case adaptiveScaffold = "AdaptiveScaffold"
    case adaptiveSlider = "AdaptiveSlider"
    case adaptiveSurface = "AdaptiveSurface"
    case adaptiveSwitch = "AdaptiveSwitch"
    case adaptiveTextButton = "AdaptiveTextButton"
    case adaptiveTheme = "AdaptiveTheme"
    case adaptiveTonalButton = "AdaptiveTonalButton"
    case adaptiveTopAppBar = "AdaptiveTopAppBar"
    case adaptiveTriStateCheckbox = "AdaptiveTriStateCheckbox"
    case adaptiveVerticalDivider = "AdaptiveVerticalDivider"
    case adaptiveWidget = "AdaptiveWidget"
    case alertDialogActionsScope_action = "AlertDialogActionsScope.action"
    case alertDialogActionsScope_cancel = "AlertDialogActionsScope.cancel"
    case alertDialogActionsScope_default = "AlertDialogActionsScope.default"
    case alertDialogActionsScope_destructive = "AlertDialogActionsScope.destructive"
    case cupertinoActionSheet = "CupertinoActionSheet"
    case cupertinoActionSheetNative = "CupertinoActionSheetNative"
    case cupertinoActivityIndicator = "CupertinoActivityIndicator"
    case cupertinoAlertDialog = "CupertinoAlertDialog"
    case cupertinoAlertDialogNative = "CupertinoAlertDialogNative"
    case cupertinoBorderedTextField = "CupertinoBorderedTextField"
    case cupertinoBottomAppBar = "CupertinoBottomAppBar"
    case cupertinoBottomSheetContent = "CupertinoBottomSheetContent"
    case cupertinoBottomSheetDefaults_DragHandle = "CupertinoBottomSheetDefaults.DragHandle"
    case cupertinoBottomSheetScaffold = "CupertinoBottomSheetScaffold"
    case cupertinoButton = "CupertinoButton"
    case cupertinoCheckBox = "CupertinoCheckBox"
    case cupertinoDatePicker = "CupertinoDatePicker"
    case cupertinoDatePickerNative = "CupertinoDatePickerNative"
    case cupertinoDateTimePicker = "CupertinoDateTimePicker"
    case cupertinoDateTimePickerNative = "CupertinoDateTimePickerNative"
    case cupertinoDivider = "CupertinoDivider"
    case cupertinoDropdownMenu = "CupertinoDropdownMenu"
    case cupertinoDropdownMenuDefaults_PickerLeadingIcon = "CupertinoDropdownMenuDefaults.PickerLeadingIcon"
    case cupertinoHorizontalDivider = "CupertinoHorizontalDivider"
    case cupertinoIcon = "CupertinoIcon"
    case cupertinoIconButton = "CupertinoIconButton"
    case cupertinoLazyColumn = "CupertinoLazyColumn"
    case cupertinoLinkIcon = "CupertinoLinkIcon"
    case cupertinoMenuScope_MenuAction = "CupertinoMenuScope.MenuAction"
    case cupertinoMenuScope_MenuDivider = "CupertinoMenuScope.MenuDivider"
    case cupertinoMenuScope_MenuItem = "CupertinoMenuScope.MenuItem"
    case cupertinoMenuScope_MenuPickerAction = "CupertinoMenuScope.MenuPickerAction"
    case cupertinoMenuScope_MenuSection = "CupertinoMenuScope.MenuSection"
    case cupertinoMenuScope_MenuTitle = "CupertinoMenuScope.MenuTitle"
    case cupertinoNavigateBackButton = "CupertinoNavigateBackButton"
    case cupertinoNavigationBar = "CupertinoNavigationBar"
    case cupertinoNavigationBarDefaults_divider = "CupertinoNavigationBarDefaults.divider"
    case cupertinoNavigationTitle = "CupertinoNavigationTitle"
    case cupertinoRangeSlider = "CupertinoRangeSlider"
    case cupertinoScaffold = "CupertinoScaffold"
    case cupertinoScaffoldPadding = "CupertinoScaffoldPadding"
    case cupertinoSearchTextField = "CupertinoSearchTextField"
    case cupertinoSearchTextFieldDefaults_cancelButton = "CupertinoSearchTextFieldDefaults.cancelButton"
    case cupertinoSearchTextFieldDefaults_leadingIcon = "CupertinoSearchTextFieldDefaults.leadingIcon"
    case cupertinoSection = "CupertinoSection"
    case cupertinoSectionDefaults_LabelChevron = "CupertinoSectionDefaults.LabelChevron"
    case cupertinoSectionDefaults_PickerButton = "CupertinoSectionDefaults.PickerButton"
    case cupertinoSectionDefaults_TextFieldClearButton = "CupertinoSectionDefaults.TextFieldClearButton"
    case cupertinoSegmentedControl = "CupertinoSegmentedControl"
    case cupertinoSegmentedControlIndicator = "CupertinoSegmentedControlIndicator"
    case cupertinoSegmentedControlTab = "CupertinoSegmentedControlTab"
    case cupertinoSlider = "CupertinoSlider"
    case cupertinoSliderDefaults_Thumb = "CupertinoSliderDefaults.Thumb"
    case cupertinoSliderDefaults_Track = "CupertinoSliderDefaults.Track"
    case cupertinoSurface = "CupertinoSurface"
    case cupertinoSwipeBox = "CupertinoSwipeBox"
    case cupertinoSwipeBoxItem = "CupertinoSwipeBoxItem"
    case cupertinoSwitch = "CupertinoSwitch"
    case cupertinoText = "CupertinoText"
    case cupertinoTextField = "CupertinoTextField"
    case cupertinoTextFieldDefaults_DecorationBox = "CupertinoTextFieldDefaults.DecorationBox"
    case cupertinoTheme = "CupertinoTheme"
    case cupertinoTimePicker = "CupertinoTimePicker"
    case cupertinoTimePickerNative = "CupertinoTimePickerNative"
    case cupertinoTopAppBar = "CupertinoTopAppBar"
    case cupertinoTopAppBarDefaults_divider = "CupertinoTopAppBarDefaults.divider"
    case cupertinoTriStateCheckBox = "CupertinoTriStateCheckBox"
    case cupertinoVerticalDivider = "CupertinoVerticalDivider"
    case cupertinoWheelPicker = "CupertinoWheelPicker"
    case lazyListScope_section = "LazyListScope.section"
    case lazyListScope_stickySection = "LazyListScope.stickySection"
    case lazySectionScope_datePicker = "LazySectionScope.datePicker"
    case lazySectionScope_dropdownMenu = "LazySectionScope.dropdownMenu"
    case lazySectionScope_item = "LazySectionScope.item"
    case lazySectionScope_items = "LazySectionScope.items"
    case lazySectionScope_link = "LazySectionScope.link"
    case lazySectionScope_switch = "LazySectionScope.switch"
    case lazySectionScope_textField = "LazySectionScope.textField"
    case lazySectionScope_timePicker = "LazySectionScope.timePicker"
    case modifier_cupertinoPickerIndicator = "Modifier.cupertinoPickerIndicator"
    case modifier_cupertinoPredictiveEnter = "Modifier.cupertinoPredictiveEnter"
    case modifier_cupertinoPredictiveExit = "Modifier.cupertinoPredictiveExit"
    case modifier_haze = "Modifier.haze"
    case modifier_sectionContainerBackground = "Modifier.sectionContainerBackground"
    case nativeAlertDialogActionsScope_action = "NativeAlertDialogActionsScope.action"
    case nativeAlertDialogActionsScope_cancel = "NativeAlertDialogActionsScope.cancel"
    case nativeAlertDialogActionsScope_default = "NativeAlertDialogActionsScope.default"
    case nativeAlertDialogActionsScope_destructive = "NativeAlertDialogActionsScope.destructive"
    case nativeChildren = "NativeChildren"
    case provideSectionStyle = "ProvideSectionStyle"
    case provideTextStyle = "ProvideTextStyle"
    case rowScope_AdaptiveNavigationBarItem = "RowScope.AdaptiveNavigationBarItem"
    case rowScope_CupertinoNavigationBarItem = "RowScope.CupertinoNavigationBarItem"
    case sectionScope_SectionDatePicker = "SectionScope.SectionDatePicker"
    case sectionScope_SectionDropdownMenu = "SectionScope.SectionDropdownMenu"
    case sectionScope_SectionItem = "SectionScope.SectionItem"
    case sectionScope_SectionLink = "SectionScope.SectionLink"
    case sectionScope_SectionTextField = "SectionScope.SectionTextField"
    case sectionScope_SectionTimePicker = "SectionScope.SectionTimePicker"
    case sharedSwipeBoxController = "SharedSwipeBoxController"
    case surface = "Surface"
    case systemBarAppearance = "SystemBarAppearance"
    case rememberCupertinoHapticFeedback = "rememberCupertinoHapticFeedback"
    case tabRowDefaults_Modifier_tabIndicatorOffset = "TabRowDefaults.Modifier.tabIndicatorOffset"
 }

/// An interactive renderer catalog. Every scoped surface is embedded in its
/// upstream parent; selecting a catalog entry never performs a product action.
public struct CupertinoCatalogView: View {
    @State private var selected: String
    @State private var checked = true
    @State private var text = "Cupertino text"
    @State private var scalar = 0.45
    @State private var range = CupertinoRangeValue(start: 0.2, end: 0.8)
    @State private var selection = 1
    @State private var presented = false
    @State private var events = 0
    @State private var lastEvent = "No interaction yet"
    @State private var symbolIndex = 0
    @State private var dateMillis: Int64 = 1_763_683_200_000
    @State private var hour = 14
    @State private var minute = 35
    @State private var navigationHasDetail = true
    @State private var swipeValue = "Collapsed"
    @State private var hapticCommand = 0
    private let showsPicker: Bool
    public init(surface: CupertinoSurfaceName = .cupertinoButton, showsPicker: Bool = true, initiallyPresented: Bool = false) {
        _selected = State(wrappedValue: surface.rawValue)
        _presented = State(wrappedValue: initiallyPresented)
        self.showsPicker = showsPicker
    }
    public var body: some View {
        CupertinoTheme {
            VStack(alignment: .leading, spacing: 10) {
                if showsPicker {
                    CupertinoText("Cupertino catalog").font(.system(size: 20, weight: .semibold))
                    Picker("Surface", selection: Binding(get: { selected }, set: { selected = $0; presented = false })) {
                        ForEach(CupertinoSurfaceName.allCases, id: { $0.rawValue }) { surface in
                            Text(surface.rawValue).tag(surface.rawValue)
                        }
                    }.accessibilityIdentifier("cupertino.catalog.surface")
                } else {
                    CupertinoText(selected).font(.system(size: 12, design: .monospaced))
                }
                CupertinoText("Events: \(events) · \(lastEvent)").font(.system(size: 12))
                    .accessibilityIdentifier("cupertino.catalog.events")
                if hasPresentationControl {
                    CupertinoButton(presented ? "Dismiss" : "Present") { presented.toggle() }
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if selected == "CupertinoIcon" || selected == "CupertinoLinkIcon" {
                    CupertinoButton("Next icon") { symbolIndex = (symbolIndex + 1) % CupertinoSymbol.allCases.count }
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                preview(selected)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            }
            .padding(16)
            .frame(maxWidth: 760, maxHeight: .infinity, alignment: .topLeading)
        }.accessibilityIdentifier("cupertino.catalog")
    }
    private var hasPresentationControl: Bool {
        selected.contains("AlertDialog") || selected.contains("ActionSheet") || selected.contains("BottomSheet") ||
            selected.contains("DropdownMenu") || selected.contains("MenuScope") || selected.hasSuffix(".dropdownMenu") ||
            selected.hasPrefix("SectionScope.SectionDatePicker") || selected.hasPrefix("SectionScope.SectionTimePicker") ||
            selected.hasSuffix(".datePicker") || selected.hasSuffix(".timePicker")
    }
    private func event(_ name: String) { events += 1; lastEvent = name }
    private func label(_ name: String, _ text: String) -> CupertinoSlot {
        CupertinoSlot(name) { CupertinoText(text) }
    }
    private func icon(_ name: String) -> CupertinoSlot {
        CupertinoSlot(name) { CupertinoIcon(CupertinoSymbol.allCases[symbolIndex], contentDescription: "Catalog symbol") }
    }
    private func base(_ name: String, props overrides: [String: PropValue] = [:], slots: [CupertinoSlot]? = nil) -> CupertinoComponent {
        var props: [String: PropValue] = [
            "enabled": .bool(true), "checked": .bool(checked), "state": .string("Indeterminate"),
            "value": .double(scalar), "valueRange": .array([.double(0), .double(1)]), "steps": .int(0),
            "selectedTabIndex": .int(selection), "selected": .bool(checked), "isSelected": .bool(checked),
            "expanded": .bool(presented), "visible": .bool(presented), "title": .string("Catalog title"), "text": .string("Cupertino text"),
            "message": .string("This is a catalog presentation."), "style": .string("InsetGrouped"),
            "imageVector": .string(CupertinoSymbol.allCases[symbolIndex].rawValue), "contentDescription": .string("Catalog symbol"),
            "selectedDateMillis": .int(Int(dateMillis)), "hour": .int(hour), "minute": .int(minute), "is24Hour": .bool(true),
            "yearRange": .array([.int(1900), .int(2100)]), "selectedItem": .int(selection),
            "items": .array([.string("First"), .string("Second"), .string("Third")]),
            "itemKeys": .array([.string("one"), .string("two"), .string("three")])
        ]
        var actions: [String: ComposableAction] = [
            "onClick": .void { checked.toggle(); event("onClick") },
            "onCheckedChange": .bool { checked = $0; event("onCheckedChange") },
            "onDismissRequest": .void { presented = false; event("onDismissRequest") },
            "onExpandedChange": .bool { presented = $0; event("onExpandedChange") },
            "onCollapsedChange": .bool { checked = $0; event("onCollapsedChange") },
            "onValueChangeFinished": .void { event("onValueChangeFinished") },
            "onBack": .void { navigationHasDetail = false; event("onBack") }, "onSubmit": .void { event("onSubmit") },
            "onStateChange": .string {
                if name == "CupertinoSwipeBox", let next = cupertinoDecode(CupertinoSwipeChange.self, $0) { swipeValue = next.value.rawValue }
                else if let next = cupertinoDecode(CupertinoSheetChange.self, $0) { presented = next.value != .hidden }
                event("onStateChange: " + $0)
            }
        ]
        if name.hasPrefix("AlertDialogActionsScope.") || name.hasPrefix("NativeAlertDialogActionsScope.") {
            actions["onClick"] = .void { presented = false; event("dialog action") }
        } else if name == "SectionScope.SectionDropdownMenu" || name == "LazySectionScope.dropdownMenu" {
            actions["onClick"] = .void { presented = true; event("open menu") }
        } else if name == "CupertinoMenuScope.MenuAction" || name == "CupertinoMenuScope.MenuPickerAction" {
            actions["onClick"] = .void { checked.toggle(); presented = false; event("menu selection") }
        }
        if name.contains("TextField") || name.hasSuffix(".textField") {
            props["value"] = .string(text)
            actions["onValueChange"] = .string { text = $0; event("onValueChange") }
        } else if name.contains("RangeSlider") {
            props["value"] = .array([.double(range.start), .double(range.end)])
            actions["onValueChange"] = .string {
                if let next = cupertinoDecode(CupertinoRangeValue.self, $0), next.isValid { range = next; event("onValueChange") }
            }
        } else { actions["onValueChange"] = .double { scalar = $0; event("onValueChange") } }
        if name == "CupertinoWheelPicker" {
            actions["onSelectionChange"] = .int { selection = $0; event("onSelectionChange") }
        } else { actions["onSelectionChange"] = .string {
            if let next = cupertinoDecode(CupertinoDateTimeSelection.self, $0), next.isValid {
                dateMillis = next.selectedDateMillis; hour = next.hour; minute = next.minute
            } else if let next = cupertinoDecode(CupertinoDateSelection.self, $0) { dateMillis = next.selectedDateMillis }
            else if let next = cupertinoDecode(CupertinoTimeSelection.self, $0), next.isValid { hour = next.hour; minute = next.minute }
            event("onSelectionChange: " + $0)
        } }
        for (key, value) in overrides { props[key] = value }
        let content = slots ?? [label("content", "Catalog content"), label("title", "Catalog title"), label("label", "Label"),
            label("placeholder", "Enter text"), icon("icon"), icon("leadingIcon"), label("caption", "Supporting caption")]
        return CupertinoComponent(name, props: props, actions: actions, slots: content)
    }
    private func preview(_ name: String) -> AnyView {
        // Alerts have no upstream visibility argument: dismissal removes them
        // from composition. Action sheets retain their actual visible state API.
        if name.contains("AlertDialog") && !presented {
            return AnyView(CupertinoText("Choose Present to display this alert."))
        }
        if name.hasPrefix("AlertDialogActionsScope.") || name.hasPrefix("NativeAlertDialogActionsScope.") {
            let native = name.hasPrefix("Native")
            return AnyView(base(native ? "CupertinoAlertDialogNative" : "CupertinoAlertDialog",
                props: ["buttonsOrientation": .string("Vertical")], slots: [label("title", "Ordered actions"), label("message", "Tap to verify the Swift event"),
                    CupertinoSlot("buttons") { base(name, props: ["style": .string("Default")], slots: [label("title", "Selected action")]) }]))
        }
        if name.contains("AlertDialog") || name.contains("ActionSheet") {
            let native = name.contains("Native")
            return AnyView(base(name, props: ["buttonsOrientation": .string("Vertical")], slots: [label("title", "Catalog dialog"), label("message", "This action is local to the catalog."),
                CupertinoSlot("buttons") {
                    CupertinoAlertAction("Default", kind: .default, native: native) { event("default"); presented = false }
                    CupertinoAlertAction("Destructive", kind: .destructive, native: native) { event("destructive"); presented = false }
                    CupertinoAlertAction("Cancel", kind: .cancel, native: native) { event("cancel"); presented = false }
                }]))
        }
        if name.hasPrefix("CupertinoMenuScope.") || name == "CupertinoDropdownMenu" || name == "CupertinoDropdownMenuDefaults.PickerLeadingIcon" {
            let entry = name == "CupertinoDropdownMenu" ? "CupertinoMenuScope.MenuAction" : name
            return AnyView(CupertinoDropdownMenu(expanded: $presented) {
                if entry == "CupertinoDropdownMenuDefaults.PickerLeadingIcon" {
                    base("CupertinoMenuScope.MenuPickerAction", slots: [label("title", "Selected choice"), CupertinoSlot("selectionIcon") { CupertinoDropdownMenuDefaults.PickerLeadingIcon() }])
                } else if entry == "CupertinoMenuScope.MenuSection" {
                    base(entry, slots: [label("title", "Menu section"), CupertinoSlot("content") { base("CupertinoMenuScope.MenuAction", slots: [label("title", "Action")]) }])
                } else { base(entry) }
            })
        }
        if name == "CupertinoBottomSheetScaffold" || name == "CupertinoBottomSheetContent" || name == "CupertinoBottomSheetDefaults.DragHandle" {
            return AnyView(base("CupertinoBottomSheetScaffold", props: ["value": .string(presented ? "Expanded" : "Hidden"),
                "detents": .string("[{\"kind\":\"fraction\",\"value\":0.5},{\"kind\":\"fraction\",\"value\":1}]")], slots: [
                label("content", "Use Present to open the sheet."),
                CupertinoSlot("sheetContent") {
                    base("CupertinoBottomSheetContent", slots: [CupertinoSlot("content") {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 16) {
                                CupertinoText("Sheet content").font(.system(size: 22, weight: .semibold))
                                CupertinoText("Drag between the half-height and full-height positions.")
                                CupertinoButton("Close sheet") { presented = false; event("sheet dismissed") }
                            }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
                        }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }]).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }, CupertinoSlot("sheetDragHandle") { CupertinoBottomSheetDefaults.DragHandle() }
            ]).frame(height: 420))
        }
        if name == "CupertinoSwipeBox" || name == "CupertinoSwipeBoxItem" || name == "SharedSwipeBoxController" {
            return AnyView(CupertinoSharedSwipeBoxController {
                base("CupertinoSwipeBox", props: ["value": .string(swipeValue), "startToEndBehavior": .string("Expandable"), "endToStartBehavior": .string("Expandable")], slots: [
                    label("content", "Swipe this row in either direction"),
                    CupertinoSlot("items") { CupertinoSwipeBoxItem(color: .blue, onClick: { event("swipe action") }, slots: [icon("icon"), label("label", "Action")]) }
                ])
            })
        }
        if name.hasPrefix("LazySectionScope.") || name.hasPrefix("LazyListScope.") || name == "CupertinoLazyColumn" {
            return AnyView(CupertinoLazyColumn {
                CupertinoLazySection(sticky: name == "LazyListScope.stickySection", slots: [label("title", "Lazy section"), CupertinoSlot("content") {
                    if name == "LazySectionScope.items" {
                        CupertinoLazyItems(0..<100, id: { $0 }) { index in CupertinoText("Lazy row \(index)") }
                    } else if name == "LazySectionScope.dropdownMenu" {
                        CupertinoLazyDropdownMenu(expanded: $presented, slots: [label("title", "Choose a setting"),
                            label("selectedLabel", "Current choice"), CupertinoSlot("content") {
                                base("CupertinoMenuScope.MenuAction", slots: [label("title", "Choose this item")])
                            }])
                    } else if name.hasPrefix("LazySectionScope.") { base(name) }
                    else { CupertinoLazyItem { CupertinoText("Section row") } }
                }])
            }.frame(height: 360))
        }
        if name.hasPrefix("SectionScope.") || name == "CupertinoSection" || name == "ProvideSectionStyle" {
            return AnyView(CupertinoSectionStyleProvider(.insetGrouped) {
                CupertinoSection(slots: [label("title", "Section heading"), label("caption", "Section caption"), CupertinoSlot("content") {
                    if name.hasPrefix("SectionScope.") { base(name, slots: [label("title", "Section row"), icon("leadingContent"),
                        CupertinoSlot("picker") {
                            if name.contains("TimePicker") { CupertinoTimePicker(selection: Binding(get: { .init(hour: hour, minute: minute) }, set: { hour = $0.hour; minute = $0.minute })) }
                            else { CupertinoDatePicker(selection: Binding(get: { .init(selectedDateMillis: dateMillis) }, set: { dateMillis = $0.selectedDateMillis })) }
                        },
                        CupertinoSlot("menu") {
                            CupertinoDropdownMenu(expanded: $presented) {
                                base("CupertinoMenuScope.MenuAction", slots: [label("title", "Choose")])
                            }
                        }]) }
                    else { CupertinoSectionItem { CupertinoText("Section row") } }
                }])
            })
        }
        if name.hasPrefix("RowScope.") {
            return AnyView(base(name.contains("Adaptive") ? "AdaptiveNavigationBar" : "CupertinoNavigationBar", slots: [CupertinoSlot("content") { base(name) }]))
        }
        if name.contains("SegmentedControl") || name == "TabRowDefaults.Modifier.tabIndicatorOffset" {
            return AnyView(CupertinoSegmentedControl(selectedTabIndex: min(selection, 1),
                slots: name == "CupertinoSegmentedControlIndicator" || name == "TabRowDefaults.Modifier.tabIndicatorOffset" ? [CupertinoSlot("indicator") {
                    if name == "TabRowDefaults.Modifier.tabIndicatorOffset" { Rectangle().fill(Color.blue).frame(height: 2).cupertinoTabIndicatorOffset(selectedTabIndex: min(selection, 1)) }
                    else { CupertinoSegmentedControlIndicator(selectedTabIndex: min(selection, 1)) }
                }] : []) {
                CupertinoSegmentedControlTab(isSelected: selection == 0, onClick: { selection = 0; event("tab zero") }) { CupertinoText("First") }
                CupertinoSegmentedControlTab(isSelected: selection == 1, onClick: { selection = 1; event("tab one") }) { CupertinoText("Second") }
            })
        }
        if name == "CupertinoSliderDefaults.Thumb" || name == "CupertinoSliderDefaults.Track" {
            return AnyView(CupertinoSlider(value: $scalar, slots: [CupertinoSlot(name.hasSuffix("Thumb") ? "thumb" : "track") {
                if name.hasSuffix("Thumb") { CupertinoSliderDefaults.Thumb() } else { CupertinoSliderDefaults.Track() }
            }]))
        }
        if name == "CupertinoTextFieldDefaults.DecorationBox" {
            return AnyView(CupertinoTextFieldDefaults.DecorationBox(valueIsEmpty: false, slots: [label("innerTextField", "Decorated text"), icon("leadingIcon"), label("placeholder", "Placeholder")]))
        }
        if name == "CupertinoSearchTextFieldDefaults.leadingIcon" || name == "CupertinoSearchTextFieldDefaults.cancelButton" {
            return AnyView(CupertinoSearchTextField(value: $text, slots: [CupertinoSlot(name.hasSuffix("leadingIcon") ? "leadingIcon" : "cancelButton") {
                if name.hasSuffix("leadingIcon") { CupertinoSearchTextFieldDefaults.LeadingIcon() }
                else { CupertinoSearchTextFieldDefaults.CancelButton(value: $text) }
            }]))
        }
        if name == "CupertinoNavigationBarDefaults.divider" { return AnyView(CupertinoNavigationBarDefaults.Divider()) }
        if name == "CupertinoTopAppBarDefaults.divider" { return AnyView(CupertinoTopAppBarDefaults.Divider()) }
        if name == "CupertinoSectionDefaults.LabelChevron" { return AnyView(CupertinoSectionDefaults.LabelChevron()) }
        if name == "CupertinoSectionDefaults.PickerButton" {
            return AnyView(CupertinoSectionDefaults.PickerButton(expanded: presented) { CupertinoText("Choose a value") })
        }
        if name == "CupertinoSectionDefaults.TextFieldClearButton" {
            return AnyView(CupertinoSectionDefaults.TextFieldClearButton(visible: !text.isEmpty) { text = ""; event("clear text") })
        }
        if name == "NativeChildren" {
            return AnyView(CupertinoNativeChildren(entries: navigationHasDetail ? [.init(id: "root", title: "Root"), .init(id: "detail", title: "Detail")] : [.init(id: "root", title: "Root")], onBack: { navigationHasDetail = false; event("onBack") }) { entry in
                VStack { CupertinoText(entry.title); CupertinoButton(navigationHasDetail ? "Back" : "Open detail") { navigationHasDetail.toggle(); event("navigation") } }
            }.frame(height: 300))
        }
        if name == "SystemBarAppearance" {
            return AnyView(CupertinoSystemBarAppearance(dark: checked) {
                CupertinoButton(checked ? "Use light system icons" : "Use dark system icons") { checked.toggle(); event("system bars") }
            })
        }
        if name == "rememberCupertinoHapticFeedback" {
            return AnyView(CupertinoHapticFeedback(type: hapticCommand == 0 ? nil : .longPress,
                commandID: hapticCommand == 0 ? nil : String(hapticCommand), onPerformed: { event("haptic dispatched") }) {
                CupertinoButton("Request long-press feedback") { hapticCommand += 1 }
            })
        }
        if name == "AdaptiveWidget" {
            return AnyView(AdaptiveWidget(cupertino: { CupertinoText("Cupertino branch") }, material: { Text("Material branch") }))
        }
        if name == "Modifier.haze" {
            return AnyView(CupertinoText("Haze: Android tint; desktop blur").padding(30).cupertinoHaze(areas: [.init(left: 0, top: 0, right: 280, bottom: 100)], backgroundColor: .white))
        }
        if name == "Modifier.cupertinoPickerIndicator" {
            return AnyView(base("CupertinoWheelPicker", props: ["indicatorStyle": .string("legacy")], slots: [CupertinoSlot("item_1") { CupertinoText("Second").cupertinoPickerIndicator(selectedItem: 1) }]))
        }
        if name == "Modifier.cupertinoPredictiveEnter" || name == "Modifier.cupertinoPredictiveExit" {
            return AnyView(base(name, props: ["progress": .double(scalar)], slots: [label("content", "Predictive transition at 45%")]).frame(height: 120))
        }
        if name == "Modifier.sectionContainerBackground" { return AnyView(CupertinoText("Section background").padding(20).cupertinoSectionContainerBackground()) }
        if name == "CupertinoScaffoldPadding" {
            return AnyView(base("CupertinoScaffold", props: ["applyContentPadding": .bool(false)], slots: [
                label("topBar", "Top bar"), label("bottomBar", "Bottom bar"),
                CupertinoSlot("content") { CupertinoScaffoldPadding { CupertinoText("Content consumes actual scaffold padding") } }
            ]).frame(height: 300))
        }
        if name.contains("Scaffold") {
            return AnyView(base(name, props: ["applyContentPadding": .bool(true)], slots: [
                label("topBar", "Top bar"), label("bottomBar", "Bottom bar"), label("content", "Scaffold content")
            ]).frame(height: 300))
        }
        return AnyView(base(name, props: name.contains("DatePicker") ? ["style": .string("Wheel")] : [:]))
    }
}
