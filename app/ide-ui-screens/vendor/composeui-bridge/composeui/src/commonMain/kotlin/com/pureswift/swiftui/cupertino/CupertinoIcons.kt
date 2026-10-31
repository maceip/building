@file:OptIn(io.github.alexzhirkevich.cupertino.adaptive.ExperimentalAdaptiveApi::class)
package com.pureswift.swiftui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.adaptive.icons.*
import io.github.alexzhirkevich.cupertino.icons.filled.*
import io.github.alexzhirkevich.cupertino.icons.outlined.*

// Complete named registry for compose-cupertino f66875aa6f3848b30c38e42a89fc99c9ac24a585.
// 833 Cupertino vectors plus 46 theme-sensitive adaptive properties; vectors stay lazy.
internal val cupertinoIconNames: Set<String> = setOf(
    "AdaptiveIcons.Outlined.AccountBox",
    "AdaptiveIcons.Outlined.AccountCircle",
    "AdaptiveIcons.Outlined.Add",
    "AdaptiveIcons.Outlined.AddCircle",
    "AdaptiveIcons.Outlined.Build",
    "AdaptiveIcons.Outlined.Call",
    "AdaptiveIcons.Outlined.Check",
    "AdaptiveIcons.Outlined.CheckCircle",
    "AdaptiveIcons.Outlined.Clear",
    "AdaptiveIcons.Outlined.Close",
    "AdaptiveIcons.Outlined.Create",
    "AdaptiveIcons.Outlined.DateRange",
    "AdaptiveIcons.Outlined.Delete",
    "AdaptiveIcons.Outlined.Done",
    "AdaptiveIcons.Outlined.Edit",
    "AdaptiveIcons.Outlined.Email",
    "AdaptiveIcons.Outlined.ExitToApp",
    "AdaptiveIcons.Outlined.Face",
    "AdaptiveIcons.Outlined.Favorite",
    "AdaptiveIcons.Outlined.FavoriteBorder",
    "AdaptiveIcons.Outlined.Home",
    "AdaptiveIcons.Outlined.Info",
    "AdaptiveIcons.Outlined.KeyboardArrowDown",
    "AdaptiveIcons.Outlined.KeyboardArrowLeft",
    "AdaptiveIcons.Outlined.KeyboardArrowRight",
    "AdaptiveIcons.Outlined.KeyboardArrowUp",
    "AdaptiveIcons.Outlined.List",
    "AdaptiveIcons.Outlined.LocationOn",
    "AdaptiveIcons.Outlined.Lock",
    "AdaptiveIcons.Outlined.MailOutline",
    "AdaptiveIcons.Outlined.Menu",
    "AdaptiveIcons.Outlined.MoreVert",
    "AdaptiveIcons.Outlined.Notifications",
    "AdaptiveIcons.Outlined.Person",
    "AdaptiveIcons.Outlined.Phone",
    "AdaptiveIcons.Outlined.Place",
    "AdaptiveIcons.Outlined.PlayArrow",
    "AdaptiveIcons.Outlined.Refresh",
    "AdaptiveIcons.Outlined.Search",
    "AdaptiveIcons.Outlined.Send",
    "AdaptiveIcons.Outlined.Settings",
    "AdaptiveIcons.Outlined.Share",
    "AdaptiveIcons.Outlined.ShoppingCart",
    "AdaptiveIcons.Outlined.Star",
    "AdaptiveIcons.Outlined.ThumbUp",
    "AdaptiveIcons.Outlined.Warning",
    "CupertinoIcons.Filled.Airtag",
    "CupertinoIcons.Filled.Alarm",
    "CupertinoIcons.Filled.Appletv",
    "CupertinoIcons.Filled.Archivebox",
    "CupertinoIcons.Filled.ArrowClockwiseCircle",
    "CupertinoIcons.Filled.ArrowCounterclockwiseCircle",
    "CupertinoIcons.Filled.ArrowCounterclockwiseIcloud",
    "CupertinoIcons.Filled.ArrowDownCircle",
    "CupertinoIcons.Filled.ArrowDownDoc",
    "CupertinoIcons.Filled.ArrowTriangle2CirclepathCamera",
    "CupertinoIcons.Filled.ArrowTriangle2CirclepathCircle",
    "CupertinoIcons.Filled.ArrowTurnUpForwardIphone",
    "CupertinoIcons.Filled.ArrowUpDoc",
    "CupertinoIcons.Filled.ArrowshapeTurnUpLeft",
    "CupertinoIcons.Filled.ArrowshapeTurnUpLeft2",
    "CupertinoIcons.Filled.Backward",
    "CupertinoIcons.Filled.BackwardEnd",
    "CupertinoIcons.Filled.Bag",
    "CupertinoIcons.Filled.BagBadgeMinus",
    "CupertinoIcons.Filled.BagBadgePlus",
    "CupertinoIcons.Filled.Balloon",
    "CupertinoIcons.Filled.Bandage",
    "CupertinoIcons.Filled.Banknote",
    "CupertinoIcons.Filled.Baseball",
    "CupertinoIcons.Filled.Basket",
    "CupertinoIcons.Filled.Basketball",
    "CupertinoIcons.Filled.BedDouble",
    "CupertinoIcons.Filled.Bell",
    "CupertinoIcons.Filled.BellAndWavesLeftAndRight",
    "CupertinoIcons.Filled.BellBadge",
    "CupertinoIcons.Filled.BellCircle",
    "CupertinoIcons.Filled.BellSlash",
    "CupertinoIcons.Filled.Binoculars",
    "CupertinoIcons.Filled.BirthdayCake",
    "CupertinoIcons.Filled.Bolt",
    "CupertinoIcons.Filled.BoltHorizontal",
    "CupertinoIcons.Filled.BoltSlash",
    "CupertinoIcons.Filled.Book",
    "CupertinoIcons.Filled.BookCircle",
    "CupertinoIcons.Filled.BookClosed",
    "CupertinoIcons.Filled.Bookmark",
    "CupertinoIcons.Filled.BookmarkSlash",
    "CupertinoIcons.Filled.Briefcase",
    "CupertinoIcons.Filled.BubbleLeft",
    "CupertinoIcons.Filled.BubbleRight",
    "CupertinoIcons.Filled.Building",
    "CupertinoIcons.Filled.Building2",
    "CupertinoIcons.Filled.Burst",
    "CupertinoIcons.Filled.Camera",
    "CupertinoIcons.Filled.CameraCircle",
    "CupertinoIcons.Filled.Capslock",
    "CupertinoIcons.Filled.Car",
    "CupertinoIcons.Filled.Cart",
    "CupertinoIcons.Filled.CartBadgeMinus",
    "CupertinoIcons.Filled.CartBadgePlus",
    "CupertinoIcons.Filled.Case",
    "CupertinoIcons.Filled.ChartBar",
    "CupertinoIcons.Filled.CheckmarkCircle",
    "CupertinoIcons.Filled.CheckmarkIcloud",
    "CupertinoIcons.Filled.CheckmarkMessage",
    "CupertinoIcons.Filled.CheckmarkSeal",
    "CupertinoIcons.Filled.CheckmarkShield",
    "CupertinoIcons.Filled.CheckmarkSquare",
    "CupertinoIcons.Filled.CircleLefthalfed",
    "CupertinoIcons.Filled.CircleRighthalfed",
    "CupertinoIcons.Filled.Clear",
    "CupertinoIcons.Filled.Clipboard",
    "CupertinoIcons.Filled.Clock",
    "CupertinoIcons.Filled.Cloud",
    "CupertinoIcons.Filled.Cone",
    "CupertinoIcons.Filled.Cpu",
    "CupertinoIcons.Filled.Creditcard",
    "CupertinoIcons.Filled.Cross",
    "CupertinoIcons.Filled.CrossCircle",
    "CupertinoIcons.Filled.CrossVial",
    "CupertinoIcons.Filled.Crown",
    "CupertinoIcons.Filled.Cube",
    "CupertinoIcons.Filled.CupAndSaucer",
    "CupertinoIcons.Filled.DeleteLeft",
    "CupertinoIcons.Filled.DeleteRight",
    "CupertinoIcons.Filled.Dice",
    "CupertinoIcons.Filled.Doc",
    "CupertinoIcons.Filled.DocBadgeArrowUp",
    "CupertinoIcons.Filled.DocBadgePlus",
    "CupertinoIcons.Filled.DocOnDoc",
    "CupertinoIcons.Filled.DocPlaintext",
    "CupertinoIcons.Filled.DocText",
    "CupertinoIcons.Filled.Drop",
    "CupertinoIcons.Filled.Ear",
    "CupertinoIcons.Filled.EllipsisBubble",
    "CupertinoIcons.Filled.EllipsisCircle",
    "CupertinoIcons.Filled.EllipsisMessage",
    "CupertinoIcons.Filled.Envelope",
    "CupertinoIcons.Filled.EnvelopeBadge",
    "CupertinoIcons.Filled.EnvelopeCircle",
    "CupertinoIcons.Filled.EnvelopeOpen",
    "CupertinoIcons.Filled.Eraser",
    "CupertinoIcons.Filled.ExclamationmarkCircle",
    "CupertinoIcons.Filled.ExclamationmarkIcloud",
    "CupertinoIcons.Filled.ExclamationmarkSquare",
    "CupertinoIcons.Filled.ExclamationmarkTriangle",
    "CupertinoIcons.Filled.Externaldrive",
    "CupertinoIcons.Filled.Eye",
    "CupertinoIcons.Filled.EyeSlash",
    "CupertinoIcons.Filled.Facemask",
    "CupertinoIcons.Filled.Fanblades",
    "CupertinoIcons.Filled.FanbladesSlash",
    "CupertinoIcons.Filled.Film",
    "CupertinoIcons.Filled.Flag",
    "CupertinoIcons.Filled.Flag2Crossed",
    "CupertinoIcons.Filled.FlagSlash",
    "CupertinoIcons.Filled.Flame",
    "CupertinoIcons.Filled.Folder",
    "CupertinoIcons.Filled.FolderBadgePlus",
    "CupertinoIcons.Filled.Football",
    "CupertinoIcons.Filled.ForkKnifeCircle",
    "CupertinoIcons.Filled.Forward",
    "CupertinoIcons.Filled.ForwardEnd",
    "CupertinoIcons.Filled.Fuelpump",
    "CupertinoIcons.Filled.Gamecontroller",
    "CupertinoIcons.Filled.Gearshape",
    "CupertinoIcons.Filled.Gearshape2",
    "CupertinoIcons.Filled.Gift",
    "CupertinoIcons.Filled.Giftcard",
    "CupertinoIcons.Filled.GlobeDesk",
    "CupertinoIcons.Filled.Graduationcap",
    "CupertinoIcons.Filled.Hammer",
    "CupertinoIcons.Filled.HandDraw",
    "CupertinoIcons.Filled.HandPointUp",
    "CupertinoIcons.Filled.HandPointUpLeft",
    "CupertinoIcons.Filled.HandRaised",
    "CupertinoIcons.Filled.HandRaisedSlash",
    "CupertinoIcons.Filled.HandTap",
    "CupertinoIcons.Filled.HandThumbsdown",
    "CupertinoIcons.Filled.HandThumbsup",
    "CupertinoIcons.Filled.HandWave",
    "CupertinoIcons.Filled.HandsSparkles",
    "CupertinoIcons.Filled.HeadphonesCircle",
    "CupertinoIcons.Filled.Heart",
    "CupertinoIcons.Filled.HeartCircle",
    "CupertinoIcons.Filled.HeartSlash",
    "CupertinoIcons.Filled.HeartTextSquare",
    "CupertinoIcons.Filled.Hifispeaker",
    "CupertinoIcons.Filled.Homepod",
    "CupertinoIcons.Filled.Homepodmini",
    "CupertinoIcons.Filled.House",
    "CupertinoIcons.Filled.Icloud",
    "CupertinoIcons.Filled.IcloudAndArrowDown",
    "CupertinoIcons.Filled.IcloudAndArrowUp",
    "CupertinoIcons.Filled.InfoBubble",
    "CupertinoIcons.Filled.InfoCircle",
    "CupertinoIcons.Filled.InfoSquare",
    "CupertinoIcons.Filled.Key",
    "CupertinoIcons.Filled.KeyIcloud",
    "CupertinoIcons.Filled.Keyboard",
    "CupertinoIcons.Filled.Lanyardcard",
    "CupertinoIcons.Filled.Leaf",
    "CupertinoIcons.Filled.Level",
    "CupertinoIcons.Filled.Lifepreserver",
    "CupertinoIcons.Filled.LightBeaconMax",
    "CupertinoIcons.Filled.Lightbulb",
    "CupertinoIcons.Filled.LightbulbSlash",
    "CupertinoIcons.Filled.LinkCircle",
    "CupertinoIcons.Filled.ListBulletCircle",
    "CupertinoIcons.Filled.ListBulletClipboard",
    "CupertinoIcons.Filled.ListClipboard",
    "CupertinoIcons.Filled.Location",
    "CupertinoIcons.Filled.Lock",
    "CupertinoIcons.Filled.LockCircle",
    "CupertinoIcons.Filled.LockOpen",
    "CupertinoIcons.Filled.LockSlash",
    "CupertinoIcons.Filled.Magazine",
    "CupertinoIcons.Filled.Mail",
    "CupertinoIcons.Filled.MailStack",
    "CupertinoIcons.Filled.Map",
    "CupertinoIcons.Filled.Medal",
    "CupertinoIcons.Filled.Megaphone",
    "CupertinoIcons.Filled.Menucard",
    "CupertinoIcons.Filled.Message",
    "CupertinoIcons.Filled.MessageBadgeed",
    "CupertinoIcons.Filled.Mic",
    "CupertinoIcons.Filled.MicSlash",
    "CupertinoIcons.Filled.MinusCircle",
    "CupertinoIcons.Filled.Moon",
    "CupertinoIcons.Filled.MoonStars",
    "CupertinoIcons.Filled.Mount",
    "CupertinoIcons.Filled.Newspaper",
    "CupertinoIcons.Filled.Opticaldisc",
    "CupertinoIcons.Filled.Paintbrush",
    "CupertinoIcons.Filled.PaintbrushPointed",
    "CupertinoIcons.Filled.Paintpalette",
    "CupertinoIcons.Filled.PaperclipCircle",
    "CupertinoIcons.Filled.Paperplane",
    "CupertinoIcons.Filled.PartyPopper",
    "CupertinoIcons.Filled.Pause",
    "CupertinoIcons.Filled.PauseCircle",
    "CupertinoIcons.Filled.Pawprint",
    "CupertinoIcons.Filled.PencilCircle",
    "CupertinoIcons.Filled.Person",
    "CupertinoIcons.Filled.Person2",
    "CupertinoIcons.Filled.PersonCircle",
    "CupertinoIcons.Filled.PersonCropCircle",
    "CupertinoIcons.Filled.PersonCropCircleBadgeMinus",
    "CupertinoIcons.Filled.PersonCropCircleBadgePlus",
    "CupertinoIcons.Filled.PersonCropSquare",
    "CupertinoIcons.Filled.PersonIcloud",
    "CupertinoIcons.Filled.PersonTextRectangle",
    "CupertinoIcons.Filled.PersonViewfinder",
    "CupertinoIcons.Filled.PersonWave2",
    "CupertinoIcons.Filled.Phone",
    "CupertinoIcons.Filled.PhoneAndWaveform",
    "CupertinoIcons.Filled.PhoneArrowDownLeft",
    "CupertinoIcons.Filled.PhoneArrowUpRight",
    "CupertinoIcons.Filled.PhoneBadgePlus",
    "CupertinoIcons.Filled.PhoneCircle",
    "CupertinoIcons.Filled.PhoneConnection",
    "CupertinoIcons.Filled.Photo",
    "CupertinoIcons.Filled.PhotoStack",
    "CupertinoIcons.Filled.Pill",
    "CupertinoIcons.Filled.Pin",
    "CupertinoIcons.Filled.PinCircle",
    "CupertinoIcons.Filled.PinSlash",
    "CupertinoIcons.Filled.Pip",
    "CupertinoIcons.Filled.Play",
    "CupertinoIcons.Filled.PlayCircle",
    "CupertinoIcons.Filled.PlusApp",
    "CupertinoIcons.Filled.PlusBubble",
    "CupertinoIcons.Filled.PlusCircle",
    "CupertinoIcons.Filled.PlusMessage",
    "CupertinoIcons.Filled.PlusSquare",
    "CupertinoIcons.Filled.Popcorn",
    "CupertinoIcons.Filled.PowerCircle",
    "CupertinoIcons.Filled.Printer",
    "CupertinoIcons.Filled.Puzzlepiece",
    "CupertinoIcons.Filled.PuzzlepieceExtension",
    "CupertinoIcons.Filled.QuestionmarkApp",
    "CupertinoIcons.Filled.QuestionmarkCircle",
    "CupertinoIcons.Filled.QuestionmarkFolder",
    "CupertinoIcons.Filled.QuestionmarkSquare",
    "CupertinoIcons.Filled.RecordCircle",
    "CupertinoIcons.Filled.RectanglePortraitAndArrowForward",
    "CupertinoIcons.Filled.RectangleStack",
    "CupertinoIcons.Filled.RotateLeft",
    "CupertinoIcons.Filled.RotateRight",
    "CupertinoIcons.Filled.Ruler",
    "CupertinoIcons.Filled.Safari",
    "CupertinoIcons.Filled.Scalemass",
    "CupertinoIcons.Filled.Scroll",
    "CupertinoIcons.Filled.ShazamLogo",
    "CupertinoIcons.Filled.Shield",
    "CupertinoIcons.Filled.ShieldLefthalfed",
    "CupertinoIcons.Filled.ShieldRighthalfed",
    "CupertinoIcons.Filled.ShieldSlash",
    "CupertinoIcons.Filled.Shippingbox",
    "CupertinoIcons.Filled.Shoeprints",
    "CupertinoIcons.Filled.Simcard",
    "CupertinoIcons.Filled.SmallcircleedCircle",
    "CupertinoIcons.Filled.Speaker",
    "CupertinoIcons.Filled.SpeakerMinus",
    "CupertinoIcons.Filled.SpeakerPlus",
    "CupertinoIcons.Filled.SpeakerSlash",
    "CupertinoIcons.Filled.SpeakerWave2",
    "CupertinoIcons.Filled.SquareAndArrowUp",
    "CupertinoIcons.Filled.SquareBottomthirdInseted",
    "CupertinoIcons.Filled.SquareOnSquare",
    "CupertinoIcons.Filled.SquareSplit1x2",
    "CupertinoIcons.Filled.SquareSplit2x1",
    "CupertinoIcons.Filled.SquareStack",
    "CupertinoIcons.Filled.SquareStack3dUp",
    "CupertinoIcons.Filled.SquareTopthirdInseted",
    "CupertinoIcons.Filled.Star",
    "CupertinoIcons.Filled.StarLeadinghalfed",
    "CupertinoIcons.Filled.StarSlash",
    "CupertinoIcons.Filled.Staroflife",
    "CupertinoIcons.Filled.Stop",
    "CupertinoIcons.Filled.StopCircle",
    "CupertinoIcons.Filled.Suitcase",
    "CupertinoIcons.Filled.SunMax",
    "CupertinoIcons.Filled.Tag",
    "CupertinoIcons.Filled.Terminal",
    "CupertinoIcons.Filled.TextBubble",
    "CupertinoIcons.Filled.Theatermasks",
    "CupertinoIcons.Filled.Trash",
    "CupertinoIcons.Filled.TrashSlash",
    "CupertinoIcons.Filled.TrayAndArrowDown",
    "CupertinoIcons.Filled.TrayAndArrowUp",
    "CupertinoIcons.Filled.Trophy",
    "CupertinoIcons.Filled.Tshirt",
    "CupertinoIcons.Filled.Tv",
    "CupertinoIcons.Filled.TvAndHifispeaker",
    "CupertinoIcons.Filled.Umbrella",
    "CupertinoIcons.Filled.Video",
    "CupertinoIcons.Filled.VideoCircle",
    "CupertinoIcons.Filled.VideoSlash",
    "CupertinoIcons.Filled.Volleyball",
    "CupertinoIcons.Filled.WalletPass",
    "CupertinoIcons.Filled.WebCamera",
    "CupertinoIcons.Filled.WifiRouter",
    "CupertinoIcons.Filled.Wineglass",
    "CupertinoIcons.Filled.XmarkApp",
    "CupertinoIcons.Filled.XmarkBin",
    "CupertinoIcons.Filled.XmarkCircle",
    "CupertinoIcons.Filled.XmarkIcloud",
    "CupertinoIcons.Filled.XmarkSeal",
    "CupertinoIcons.Filled.XmarkShield",
    "CupertinoIcons.Filled._4kTv",
    "CupertinoIcons.Outlined.Airplane",
    "CupertinoIcons.Outlined.AirplaneArrival",
    "CupertinoIcons.Outlined.AirplaneDeparture",
    "CupertinoIcons.Outlined.Airplayaudio",
    "CupertinoIcons.Outlined.Airpods",
    "CupertinoIcons.Outlined.AirpodsGen3",
    "CupertinoIcons.Outlined.Airpodsmax",
    "CupertinoIcons.Outlined.Airpodspro",
    "CupertinoIcons.Outlined.Airtag",
    "CupertinoIcons.Outlined.Alarm",
    "CupertinoIcons.Outlined.Alt",
    "CupertinoIcons.Outlined.Angle",
    "CupertinoIcons.Outlined.AntennaRadiowavesLeftAndRight",
    "CupertinoIcons.Outlined.AntennaRadiowavesLeftAndRightSlash",
    "CupertinoIcons.Outlined.AppleLogo",
    "CupertinoIcons.Outlined.Applepencil",
    "CupertinoIcons.Outlined.Appletv",
    "CupertinoIcons.Outlined.Applewatch",
    "CupertinoIcons.Outlined.ApplewatchRadiowavesLeftAndRight",
    "CupertinoIcons.Outlined.ApplewatchWatchface",
    "CupertinoIcons.Outlined.Archivebox",
    "CupertinoIcons.Outlined.Arrow3Trianglepath",
    "CupertinoIcons.Outlined.ArrowClockwise",
    "CupertinoIcons.Outlined.ArrowCounterclockwise",
    "CupertinoIcons.Outlined.ArrowCounterclockwiseIcloud",
    "CupertinoIcons.Outlined.ArrowDown",
    "CupertinoIcons.Outlined.ArrowDownAndLineHorizontalAndArrowUp",
    "CupertinoIcons.Outlined.ArrowDownCircle",
    "CupertinoIcons.Outlined.ArrowDownDoc",
    "CupertinoIcons.Outlined.ArrowDownRightAndArrowUpLeft",
    "CupertinoIcons.Outlined.ArrowDownToLine",
    "CupertinoIcons.Outlined.ArrowLeftAndRight",
    "CupertinoIcons.Outlined.ArrowLeftArrowRight",
    "CupertinoIcons.Outlined.ArrowTriangle2Circlepath",
    "CupertinoIcons.Outlined.ArrowTriangle2CirclepathCamera",
    "CupertinoIcons.Outlined.ArrowTriangleBranch",
    "CupertinoIcons.Outlined.ArrowTurnDownLeft",
    "CupertinoIcons.Outlined.ArrowTurnDownRight",
    "CupertinoIcons.Outlined.ArrowTurnRightUp",
    "CupertinoIcons.Outlined.ArrowTurnUpForwardIphone",
    "CupertinoIcons.Outlined.ArrowTurnUpLeft",
    "CupertinoIcons.Outlined.ArrowTurnUpRight",
    "CupertinoIcons.Outlined.ArrowUpAndDown",
    "CupertinoIcons.Outlined.ArrowUpArrowDown",
    "CupertinoIcons.Outlined.ArrowUpDoc",
    "CupertinoIcons.Outlined.ArrowUpLeftAndArrowDownRight",
    "CupertinoIcons.Outlined.ArrowUturnLeft",
    "CupertinoIcons.Outlined.ArrowUturnRight",
    "CupertinoIcons.Outlined.ArrowshapeTurnUpLeft",
    "CupertinoIcons.Outlined.ArrowshapeTurnUpLeft2",
    "CupertinoIcons.Outlined.At",
    "CupertinoIcons.Outlined.Backward",
    "CupertinoIcons.Outlined.BackwardEnd",
    "CupertinoIcons.Outlined.Bag",
    "CupertinoIcons.Outlined.BagBadgeMinus",
    "CupertinoIcons.Outlined.BagBadgePlus",
    "CupertinoIcons.Outlined.Balloon",
    "CupertinoIcons.Outlined.Bandage",
    "CupertinoIcons.Outlined.Banknote",
    "CupertinoIcons.Outlined.Barcode",
    "CupertinoIcons.Outlined.BarcodeViewfinder",
    "CupertinoIcons.Outlined.Baseball",
    "CupertinoIcons.Outlined.Basket",
    "CupertinoIcons.Outlined.Basketball",
    "CupertinoIcons.Outlined.Battery100",
    "CupertinoIcons.Outlined.BedDouble",
    "CupertinoIcons.Outlined.Bell",
    "CupertinoIcons.Outlined.BellAndWavesLeftAndRight",
    "CupertinoIcons.Outlined.BellBadge",
    "CupertinoIcons.Outlined.BellCircle",
    "CupertinoIcons.Outlined.BellSlash",
    "CupertinoIcons.Outlined.Bicycle",
    "CupertinoIcons.Outlined.Binoculars",
    "CupertinoIcons.Outlined.BirthdayCake",
    "CupertinoIcons.Outlined.Bitcoinsign",
    "CupertinoIcons.Outlined.Bolt",
    "CupertinoIcons.Outlined.BoltHorizontal",
    "CupertinoIcons.Outlined.BoltSlash",
    "CupertinoIcons.Outlined.Book",
    "CupertinoIcons.Outlined.BookCircle",
    "CupertinoIcons.Outlined.BookClosed",
    "CupertinoIcons.Outlined.Bookmark",
    "CupertinoIcons.Outlined.BookmarkSlash",
    "CupertinoIcons.Outlined.Brain",
    "CupertinoIcons.Outlined.BrainHeadProfile",
    "CupertinoIcons.Outlined.Briefcase",
    "CupertinoIcons.Outlined.BubbleLeft",
    "CupertinoIcons.Outlined.BubbleRight",
    "CupertinoIcons.Outlined.Building",
    "CupertinoIcons.Outlined.Building2",
    "CupertinoIcons.Outlined.Burn",
    "CupertinoIcons.Outlined.Burst",
    "CupertinoIcons.Outlined.CableConnector",
    "CupertinoIcons.Outlined.CableConnectorHorizontal",
    "CupertinoIcons.Outlined.Calendar",
    "CupertinoIcons.Outlined.CalendarBadgePlus",
    "CupertinoIcons.Outlined.Camera",
    "CupertinoIcons.Outlined.CameraCircle",
    "CupertinoIcons.Outlined.CameraFilters",
    "CupertinoIcons.Outlined.CameraViewfinder",
    "CupertinoIcons.Outlined.Candybarphone",
    "CupertinoIcons.Outlined.Capslock",
    "CupertinoIcons.Outlined.Car",
    "CupertinoIcons.Outlined.Cart",
    "CupertinoIcons.Outlined.CartBadgeMinus",
    "CupertinoIcons.Outlined.CartBadgePlus",
    "CupertinoIcons.Outlined.Case",
    "CupertinoIcons.Outlined.Centsign",
    "CupertinoIcons.Outlined.Character",
    "CupertinoIcons.Outlined.ChartBar",
    "CupertinoIcons.Outlined.ChartLineDowntrendXyaxis",
    "CupertinoIcons.Outlined.ChartLineUptrendXyaxis",
    "CupertinoIcons.Outlined.CheckerboardShield",
    "CupertinoIcons.Outlined.Checklist",
    "CupertinoIcons.Outlined.ChecklistChecked",
    "CupertinoIcons.Outlined.ChecklistUnchecked",
    "CupertinoIcons.Outlined.Checkmark",
    "CupertinoIcons.Outlined.CheckmarkCircle",
    "CupertinoIcons.Outlined.CheckmarkIcloud",
    "CupertinoIcons.Outlined.CheckmarkMessage",
    "CupertinoIcons.Outlined.CheckmarkSeal",
    "CupertinoIcons.Outlined.CheckmarkShield",
    "CupertinoIcons.Outlined.CheckmarkSquare",
    "CupertinoIcons.Outlined.ChevronBackward",
    "CupertinoIcons.Outlined.ChevronDown",
    "CupertinoIcons.Outlined.ChevronForward",
    "CupertinoIcons.Outlined.ChevronLeftForwardslashChevronRight",
    "CupertinoIcons.Outlined.ChevronUp",
    "CupertinoIcons.Outlined.Clear",
    "CupertinoIcons.Outlined.Clipboard",
    "CupertinoIcons.Outlined.Clock",
    "CupertinoIcons.Outlined.ClockArrowCirclepath",
    "CupertinoIcons.Outlined.Cloud",
    "CupertinoIcons.Outlined.Command",
    "CupertinoIcons.Outlined.CompassDrawing",
    "CupertinoIcons.Outlined.Cone",
    "CupertinoIcons.Outlined.Cpu",
    "CupertinoIcons.Outlined.Creditcard",
    "CupertinoIcons.Outlined.CreditcardTrianglebadgeExclamationmark",
    "CupertinoIcons.Outlined.Crop",
    "CupertinoIcons.Outlined.CropRotate",
    "CupertinoIcons.Outlined.Cross",
    "CupertinoIcons.Outlined.CrossCircle",
    "CupertinoIcons.Outlined.CrossVial",
    "CupertinoIcons.Outlined.Crown",
    "CupertinoIcons.Outlined.Cube",
    "CupertinoIcons.Outlined.CupAndSaucer",
    "CupertinoIcons.Outlined.Curlybraces",
    "CupertinoIcons.Outlined.CursorarrowRays",
    "CupertinoIcons.Outlined.DeleteLeft",
    "CupertinoIcons.Outlined.DeleteRight",
    "CupertinoIcons.Outlined.Desktopcomputer",
    "CupertinoIcons.Outlined.Dice",
    "CupertinoIcons.Outlined.Display",
    "CupertinoIcons.Outlined.Divide",
    "CupertinoIcons.Outlined.Doc",
    "CupertinoIcons.Outlined.DocBadgeArrowUp",
    "CupertinoIcons.Outlined.DocBadgePlus",
    "CupertinoIcons.Outlined.DocOnDoc",
    "CupertinoIcons.Outlined.DocPlaintext",
    "CupertinoIcons.Outlined.DocText",
    "CupertinoIcons.Outlined.DocTextMagnifyingglass",
    "CupertinoIcons.Outlined.Dollarsign",
    "CupertinoIcons.Outlined.DollarsignArrowCirclepath",
    "CupertinoIcons.Outlined.DoorLeftHandClosed",
    "CupertinoIcons.Outlined.DoorLeftHandOpen",
    "CupertinoIcons.Outlined.DotRadiowavesLeftAndRight",
    "CupertinoIcons.Outlined.DotRadiowavesUpForward",
    "CupertinoIcons.Outlined.Drop",
    "CupertinoIcons.Outlined.Ear",
    "CupertinoIcons.Outlined.Earpods",
    "CupertinoIcons.Outlined.Ellipsis",
    "CupertinoIcons.Outlined.EllipsisBubble",
    "CupertinoIcons.Outlined.EllipsisCircle",
    "CupertinoIcons.Outlined.EllipsisCurlybraces",
    "CupertinoIcons.Outlined.EllipsisMessage",
    "CupertinoIcons.Outlined.Envelope",
    "CupertinoIcons.Outlined.EnvelopeBadge",
    "CupertinoIcons.Outlined.EnvelopeCircle",
    "CupertinoIcons.Outlined.EnvelopeOpen",
    "CupertinoIcons.Outlined.Eraser",
    "CupertinoIcons.Outlined.Eurosign",
    "CupertinoIcons.Outlined.Exclamationmark",
    "CupertinoIcons.Outlined.Exclamationmark2",
    "CupertinoIcons.Outlined.Exclamationmark3",
    "CupertinoIcons.Outlined.ExclamationmarkArrowTriangle2Circlepath",
    "CupertinoIcons.Outlined.ExclamationmarkCircle",
    "CupertinoIcons.Outlined.ExclamationmarkIcloud",
    "CupertinoIcons.Outlined.ExclamationmarkSquare",
    "CupertinoIcons.Outlined.ExclamationmarkTriangle",
    "CupertinoIcons.Outlined.Externaldrive",
    "CupertinoIcons.Outlined.Eye",
    "CupertinoIcons.Outlined.EyeSlash",
    "CupertinoIcons.Outlined.Eyebrow",
    "CupertinoIcons.Outlined.Eyedropper",
    "CupertinoIcons.Outlined.Eyeglasses",
    "CupertinoIcons.Outlined.Eyes",
    "CupertinoIcons.Outlined.FaceSmiling",
    "CupertinoIcons.Outlined.FaceSmilingInverse",
    "CupertinoIcons.Outlined.Faceid",
    "CupertinoIcons.Outlined.Facemask",
    "CupertinoIcons.Outlined.Fanblades",
    "CupertinoIcons.Outlined.FanbladesSlash",
    "CupertinoIcons.Outlined.Fibrechannel",
    "CupertinoIcons.Outlined.FigureStand",
    "CupertinoIcons.Outlined.FigureWalk",
    "CupertinoIcons.Outlined.Film",
    "CupertinoIcons.Outlined.Flag",
    "CupertinoIcons.Outlined.Flag2Crossed",
    "CupertinoIcons.Outlined.FlagCheckered2Crossed",
    "CupertinoIcons.Outlined.FlagSlash",
    "CupertinoIcons.Outlined.Flame",
    "CupertinoIcons.Outlined.Flowchart",
    "CupertinoIcons.Outlined.Folder",
    "CupertinoIcons.Outlined.FolderBadgePlus",
    "CupertinoIcons.Outlined.Football",
    "CupertinoIcons.Outlined.ForkKnife",
    "CupertinoIcons.Outlined.ForkKnifeCircle",
    "CupertinoIcons.Outlined.Forward",
    "CupertinoIcons.Outlined.ForwardEnd",
    "CupertinoIcons.Outlined.Francsign",
    "CupertinoIcons.Outlined.Fuelpump",
    "CupertinoIcons.Outlined.Gamecontroller",
    "CupertinoIcons.Outlined.Gear",
    "CupertinoIcons.Outlined.Gearshape",
    "CupertinoIcons.Outlined.Gearshape2",
    "CupertinoIcons.Outlined.Gift",
    "CupertinoIcons.Outlined.Giftcard",
    "CupertinoIcons.Outlined.GlobeDesk",
    "CupertinoIcons.Outlined.Gobackward",
    "CupertinoIcons.Outlined.Goforward",
    "CupertinoIcons.Outlined.Graduationcap",
    "CupertinoIcons.Outlined.Grid",
    "CupertinoIcons.Outlined.Hammer",
    "CupertinoIcons.Outlined.HandDraw",
    "CupertinoIcons.Outlined.HandPointUp",
    "CupertinoIcons.Outlined.HandPointUpLeft",
    "CupertinoIcons.Outlined.HandRaised",
    "CupertinoIcons.Outlined.HandRaisedSlash",
    "CupertinoIcons.Outlined.HandTap",
    "CupertinoIcons.Outlined.HandThumbsdown",
    "CupertinoIcons.Outlined.HandThumbsup",
    "CupertinoIcons.Outlined.HandWave",
    "CupertinoIcons.Outlined.HandsSparkles",
    "CupertinoIcons.Outlined.Headphones",
    "CupertinoIcons.Outlined.HeadphonesCircle",
    "CupertinoIcons.Outlined.Heart",
    "CupertinoIcons.Outlined.HeartCircle",
    "CupertinoIcons.Outlined.HeartSlash",
    "CupertinoIcons.Outlined.HeartTextSquare",
    "CupertinoIcons.Outlined.Hifispeaker",
    "CupertinoIcons.Outlined.Highlighter",
    "CupertinoIcons.Outlined.Homekit",
    "CupertinoIcons.Outlined.Homepod",
    "CupertinoIcons.Outlined.Homepodmini",
    "CupertinoIcons.Outlined.Hourglass",
    "CupertinoIcons.Outlined.House",
    "CupertinoIcons.Outlined.Hryvniasign",
    "CupertinoIcons.Outlined.Icloud",
    "CupertinoIcons.Outlined.IcloudAndArrowDown",
    "CupertinoIcons.Outlined.IcloudAndArrowUp",
    "CupertinoIcons.Outlined.Infinity",
    "CupertinoIcons.Outlined.Info",
    "CupertinoIcons.Outlined.InfoBubble",
    "CupertinoIcons.Outlined.InfoCircle",
    "CupertinoIcons.Outlined.InfoSquare",
    "CupertinoIcons.Outlined.Ipad",
    "CupertinoIcons.Outlined.IpadAndIphone",
    "CupertinoIcons.Outlined.IpadHomebutton",
    "CupertinoIcons.Outlined.Iphone",
    "CupertinoIcons.Outlined.IphoneBadgePlay",
    "CupertinoIcons.Outlined.IphoneHomebutton",
    "CupertinoIcons.Outlined.IphoneHomebuttonRadiowavesLeftAndRight",
    "CupertinoIcons.Outlined.IphoneRadiowavesLeftAndRight",
    "CupertinoIcons.Outlined.Key",
    "CupertinoIcons.Outlined.KeyIcloud",
    "CupertinoIcons.Outlined.Keyboard",
    "CupertinoIcons.Outlined.Lanyardcard",
    "CupertinoIcons.Outlined.Laptopcomputer",
    "CupertinoIcons.Outlined.LaptopcomputerAndIpad",
    "CupertinoIcons.Outlined.LaptopcomputerAndIphone",
    "CupertinoIcons.Outlined.Leaf",
    "CupertinoIcons.Outlined.Level",
    "CupertinoIcons.Outlined.Lifepreserver",
    "CupertinoIcons.Outlined.LightBeaconMax",
    "CupertinoIcons.Outlined.LightMax",
    "CupertinoIcons.Outlined.LightMin",
    "CupertinoIcons.Outlined.Lightbulb",
    "CupertinoIcons.Outlined.LightbulbSlash",
    "CupertinoIcons.Outlined.Link",
    "CupertinoIcons.Outlined.LinkBadgePlus",
    "CupertinoIcons.Outlined.LinkCircle",
    "CupertinoIcons.Outlined.Lirasign",
    "CupertinoIcons.Outlined.ListBullet",
    "CupertinoIcons.Outlined.ListBulletCircle",
    "CupertinoIcons.Outlined.ListBulletClipboard",
    "CupertinoIcons.Outlined.ListBulletIndent",
    "CupertinoIcons.Outlined.ListClipboard",
    "CupertinoIcons.Outlined.ListNumber",
    "CupertinoIcons.Outlined.Livephoto",
    "CupertinoIcons.Outlined.Location",
    "CupertinoIcons.Outlined.Lock",
    "CupertinoIcons.Outlined.LockCircle",
    "CupertinoIcons.Outlined.LockOpen",
    "CupertinoIcons.Outlined.LockSlash",
    "CupertinoIcons.Outlined.Macwindow",
    "CupertinoIcons.Outlined.MacwindowBadgePlus",
    "CupertinoIcons.Outlined.Magazine",
    "CupertinoIcons.Outlined.MagnifyingGlass",
    "CupertinoIcons.Outlined.Mail",
    "CupertinoIcons.Outlined.MailStack",
    "CupertinoIcons.Outlined.Map",
    "CupertinoIcons.Outlined.Mappin",
    "CupertinoIcons.Outlined.MappinAndEllipse",
    "CupertinoIcons.Outlined.MappinSlash",
    "CupertinoIcons.Outlined.Medal",
    "CupertinoIcons.Outlined.Megaphone",
    "CupertinoIcons.Outlined.Memories",
    "CupertinoIcons.Outlined.MenubarRectangle",
    "CupertinoIcons.Outlined.Menucard",
    "CupertinoIcons.Outlined.Message",
    "CupertinoIcons.Outlined.MessageBadge",
    "CupertinoIcons.Outlined.Mic",
    "CupertinoIcons.Outlined.MicSlash",
    "CupertinoIcons.Outlined.Minus",
    "CupertinoIcons.Outlined.MinusCircle",
    "CupertinoIcons.Outlined.MinusMagnifyingglass",
    "CupertinoIcons.Outlined.Moon",
    "CupertinoIcons.Outlined.MoonStars",
    "CupertinoIcons.Outlined.Mount",
    "CupertinoIcons.Outlined.Multiply",
    "CupertinoIcons.Outlined.MusicMic",
    "CupertinoIcons.Outlined.MusicNote",
    "CupertinoIcons.Outlined.MusicNoteList",
    "CupertinoIcons.Outlined.MusicQuarternote3",
    "CupertinoIcons.Outlined.Network",
    "CupertinoIcons.Outlined.Newspaper",
    "CupertinoIcons.Outlined.Nosign",
    "CupertinoIcons.Outlined.NoteText",
    "CupertinoIcons.Outlined.NoteTextBadgePlus",
    "CupertinoIcons.Outlined.Number",
    "CupertinoIcons.Outlined.Opticaldisc",
    "CupertinoIcons.Outlined.Option",
    "CupertinoIcons.Outlined.Paintbrush",
    "CupertinoIcons.Outlined.PaintbrushPointed",
    "CupertinoIcons.Outlined.Paintpalette",
    "CupertinoIcons.Outlined.Paperclip",
    "CupertinoIcons.Outlined.PaperclipBadgeEllipsis",
    "CupertinoIcons.Outlined.PaperclipCircle",
    "CupertinoIcons.Outlined.Paperplane",
    "CupertinoIcons.Outlined.Paragraphsign",
    "CupertinoIcons.Outlined.PartyPopper",
    "CupertinoIcons.Outlined.Pause",
    "CupertinoIcons.Outlined.PauseCircle",
    "CupertinoIcons.Outlined.Pawprint",
    "CupertinoIcons.Outlined.Pencil",
    "CupertinoIcons.Outlined.PencilCircle",
    "CupertinoIcons.Outlined.PencilTipCropCircle",
    "CupertinoIcons.Outlined.Percent",
    "CupertinoIcons.Outlined.Person",
    "CupertinoIcons.Outlined.Person2",
    "CupertinoIcons.Outlined.PersonAndBackgroundDotted",
    "CupertinoIcons.Outlined.PersonCircle",
    "CupertinoIcons.Outlined.PersonCropCircle",
    "CupertinoIcons.Outlined.PersonCropCircleBadgeMinus",
    "CupertinoIcons.Outlined.PersonCropCircleBadgePlus",
    "CupertinoIcons.Outlined.PersonCropSquare",
    "CupertinoIcons.Outlined.PersonIcloud",
    "CupertinoIcons.Outlined.PersonTextRectangle",
    "CupertinoIcons.Outlined.PersonWave2",
    "CupertinoIcons.Outlined.Personalhotspot",
    "CupertinoIcons.Outlined.Phone",
    "CupertinoIcons.Outlined.PhoneAndWaveform",
    "CupertinoIcons.Outlined.PhoneArrowDownLeft",
    "CupertinoIcons.Outlined.PhoneArrowUpRight",
    "CupertinoIcons.Outlined.PhoneBadgePlus",
    "CupertinoIcons.Outlined.PhoneCircle",
    "CupertinoIcons.Outlined.PhoneConnection",
    "CupertinoIcons.Outlined.Photo",
    "CupertinoIcons.Outlined.PhotoStack",
    "CupertinoIcons.Outlined.PhotoTv",
    "CupertinoIcons.Outlined.Pill",
    "CupertinoIcons.Outlined.Pin",
    "CupertinoIcons.Outlined.PinCircle",
    "CupertinoIcons.Outlined.PinSlash",
    "CupertinoIcons.Outlined.Pip",
    "CupertinoIcons.Outlined.PipEnter",
    "CupertinoIcons.Outlined.PipExit",
    "CupertinoIcons.Outlined.Play",
    "CupertinoIcons.Outlined.PlayCircle",
    "CupertinoIcons.Outlined.PlayDisplay",
    "CupertinoIcons.Outlined.Plus",
    "CupertinoIcons.Outlined.PlusApp",
    "CupertinoIcons.Outlined.PlusBubble",
    "CupertinoIcons.Outlined.PlusCircle",
    "CupertinoIcons.Outlined.PlusMagnifyingglass",
    "CupertinoIcons.Outlined.PlusMessage",
    "CupertinoIcons.Outlined.PlusSquare",
    "CupertinoIcons.Outlined.PlusViewfinder",
    "CupertinoIcons.Outlined.Popcorn",
    "CupertinoIcons.Outlined.Power",
    "CupertinoIcons.Outlined.PowerCircle",
    "CupertinoIcons.Outlined.Printer",
    "CupertinoIcons.Outlined.Puzzlepiece",
    "CupertinoIcons.Outlined.PuzzlepieceExtension",
    "CupertinoIcons.Outlined.Qrcode",
    "CupertinoIcons.Outlined.QrcodeViewfinder",
    "CupertinoIcons.Outlined.Questionmark",
    "CupertinoIcons.Outlined.QuestionmarkApp",
    "CupertinoIcons.Outlined.QuestionmarkCircle",
    "CupertinoIcons.Outlined.QuestionmarkFolder",
    "CupertinoIcons.Outlined.QuestionmarkSquare",
    "CupertinoIcons.Outlined.QuoteClosing",
    "CupertinoIcons.Outlined.QuoteOpening",
    "CupertinoIcons.Outlined.Rays",
    "CupertinoIcons.Outlined.RecordCircle",
    "CupertinoIcons.Outlined.Recordingtape",
    "CupertinoIcons.Outlined.RectangleArrowtriangle2Outward",
    "CupertinoIcons.Outlined.RectangleConnectedToLineBelow",
    "CupertinoIcons.Outlined.RectanglePortraitAndArrowForward",
    "CupertinoIcons.Outlined.RectanglePortraitArrowtriangle2Outward",
    "CupertinoIcons.Outlined.RectangleStack",
    "CupertinoIcons.Outlined.Repeat",
    "CupertinoIcons.Outlined.Rosette",
    "CupertinoIcons.Outlined.Rotate3d",
    "CupertinoIcons.Outlined.RotateLeft",
    "CupertinoIcons.Outlined.RotateRight",
    "CupertinoIcons.Outlined.Rublesign",
    "CupertinoIcons.Outlined.Ruler",
    "CupertinoIcons.Outlined.Safari",
    "CupertinoIcons.Outlined.Scalemass",
    "CupertinoIcons.Outlined.Scissors",
    "CupertinoIcons.Outlined.Scope",
    "CupertinoIcons.Outlined.Scribble",
    "CupertinoIcons.Outlined.ScribbleVariable",
    "CupertinoIcons.Outlined.Scroll",
    "CupertinoIcons.Outlined.ServerRack",
    "CupertinoIcons.Outlined.Shareplay",
    "CupertinoIcons.Outlined.ShareplaySlash",
    "CupertinoIcons.Outlined.ShazamLogo",
    "CupertinoIcons.Outlined.Shield",
    "CupertinoIcons.Outlined.ShieldSlash",
    "CupertinoIcons.Outlined.Shippingbox",
    "CupertinoIcons.Outlined.Shuffle",
    "CupertinoIcons.Outlined.SidebarLeft",
    "CupertinoIcons.Outlined.SidebarRight",
    "CupertinoIcons.Outlined.Simcard",
    "CupertinoIcons.Outlined.Skew",
    "CupertinoIcons.Outlined.SliderHorizontal3",
    "CupertinoIcons.Outlined.SliderVertical3",
    "CupertinoIcons.Outlined.Snowflake",
    "CupertinoIcons.Outlined.Soccerball",
    "CupertinoIcons.Outlined.Space",
    "CupertinoIcons.Outlined.Sparkle",
    "CupertinoIcons.Outlined.Sparkles",
    "CupertinoIcons.Outlined.Speaker",
    "CupertinoIcons.Outlined.SpeakerMinus",
    "CupertinoIcons.Outlined.SpeakerPlus",
    "CupertinoIcons.Outlined.SpeakerSlash",
    "CupertinoIcons.Outlined.SpeakerWave2",
    "CupertinoIcons.Outlined.Speedometer",
    "CupertinoIcons.Outlined.Square3Layers3dDownLeft",
    "CupertinoIcons.Outlined.Square3Layers3dDownRight",
    "CupertinoIcons.Outlined.SquareAndArrowUp",
    "CupertinoIcons.Outlined.SquareAndPencil",
    "CupertinoIcons.Outlined.SquareOnSquare",
    "CupertinoIcons.Outlined.SquareSplit1x2",
    "CupertinoIcons.Outlined.SquareSplit2x1",
    "CupertinoIcons.Outlined.SquareStack",
    "CupertinoIcons.Outlined.SquareStack3dUp",
    "CupertinoIcons.Outlined.Star",
    "CupertinoIcons.Outlined.StarSlash",
    "CupertinoIcons.Outlined.Staroflife",
    "CupertinoIcons.Outlined.Sterlingsign",
    "CupertinoIcons.Outlined.Stethoscope",
    "CupertinoIcons.Outlined.Stop",
    "CupertinoIcons.Outlined.StopCircle",
    "CupertinoIcons.Outlined.Suitcase",
    "CupertinoIcons.Outlined.Sum",
    "CupertinoIcons.Outlined.SunMax",
    "CupertinoIcons.Outlined.Swift",
    "CupertinoIcons.Outlined.Tag",
    "CupertinoIcons.Outlined.Target",
    "CupertinoIcons.Outlined.TennisRacket",
    "CupertinoIcons.Outlined.Terminal",
    "CupertinoIcons.Outlined.TextBubble",
    "CupertinoIcons.Outlined.TextMagnifyingglass",
    "CupertinoIcons.Outlined.Theatermasks",
    "CupertinoIcons.Outlined.Timer",
    "CupertinoIcons.Outlined.Touchid",
    "CupertinoIcons.Outlined.Trash",
    "CupertinoIcons.Outlined.TrashSlash",
    "CupertinoIcons.Outlined.TrayAndArrowDown",
    "CupertinoIcons.Outlined.TrayAndArrowUp",
    "CupertinoIcons.Outlined.Trophy",
    "CupertinoIcons.Outlined.Tshirt",
    "CupertinoIcons.Outlined.Tv",
    "CupertinoIcons.Outlined.Umbrella",
    "CupertinoIcons.Outlined.Video",
    "CupertinoIcons.Outlined.VideoCircle",
    "CupertinoIcons.Outlined.VideoSlash",
    "CupertinoIcons.Outlined.Volleyball",
    "CupertinoIcons.Outlined.WalletPass",
    "CupertinoIcons.Outlined.WandAndStars",
    "CupertinoIcons.Outlined.WandAndStarsInverse",
    "CupertinoIcons.Outlined.Waveform",
    "CupertinoIcons.Outlined.WaveformAndMagnifyingglass",
    "CupertinoIcons.Outlined.WaveformAndMic",
    "CupertinoIcons.Outlined.WaveformPathEcg",
    "CupertinoIcons.Outlined.WebCamera",
    "CupertinoIcons.Outlined.Wifi",
    "CupertinoIcons.Outlined.WifiExclamationmark",
    "CupertinoIcons.Outlined.WifiRouter",
    "CupertinoIcons.Outlined.WifiSlash",
    "CupertinoIcons.Outlined.Wind",
    "CupertinoIcons.Outlined.Wineglass",
    "CupertinoIcons.Outlined.WrenchAndScrewdriver",
    "CupertinoIcons.Outlined.Xmark",
    "CupertinoIcons.Outlined.XmarkApp",
    "CupertinoIcons.Outlined.XmarkBin",
    "CupertinoIcons.Outlined.XmarkCircle",
    "CupertinoIcons.Outlined.XmarkIcloud",
    "CupertinoIcons.Outlined.XmarkSeal",
    "CupertinoIcons.Outlined.XmarkShield",
    "CupertinoIcons.Outlined.Yensign",
    "CupertinoIcons.Outlined.Zzz",
    "CupertinoIcons.Outlined._4kTv",
)

@Composable
internal fun cupertinoIcon(name: String?): ImageVector? {
    val resolved = name?.replace("CupertinoIcons.Default.", "CupertinoIcons.Outlined.") ?: return null
    return when (resolved) {
        "AdaptiveIcons.Outlined.AccountBox" -> AdaptiveIcons.Outlined.AccountBox
        "AdaptiveIcons.Outlined.AccountCircle" -> AdaptiveIcons.Outlined.AccountCircle
        "AdaptiveIcons.Outlined.Add" -> AdaptiveIcons.Outlined.Add
        "AdaptiveIcons.Outlined.AddCircle" -> AdaptiveIcons.Outlined.AddCircle
        "AdaptiveIcons.Outlined.Build" -> AdaptiveIcons.Outlined.Build
        "AdaptiveIcons.Outlined.Call" -> AdaptiveIcons.Outlined.Call
        "AdaptiveIcons.Outlined.Check" -> AdaptiveIcons.Outlined.Check
        "AdaptiveIcons.Outlined.CheckCircle" -> AdaptiveIcons.Outlined.CheckCircle
        "AdaptiveIcons.Outlined.Clear" -> AdaptiveIcons.Outlined.Clear
        "AdaptiveIcons.Outlined.Close" -> AdaptiveIcons.Outlined.Close
        "AdaptiveIcons.Outlined.Create" -> AdaptiveIcons.Outlined.Create
        "AdaptiveIcons.Outlined.DateRange" -> AdaptiveIcons.Outlined.DateRange
        "AdaptiveIcons.Outlined.Delete" -> AdaptiveIcons.Outlined.Delete
        "AdaptiveIcons.Outlined.Done" -> AdaptiveIcons.Outlined.Done
        "AdaptiveIcons.Outlined.Edit" -> AdaptiveIcons.Outlined.Edit
        "AdaptiveIcons.Outlined.Email" -> AdaptiveIcons.Outlined.Email
        "AdaptiveIcons.Outlined.ExitToApp" -> AdaptiveIcons.Outlined.ExitToApp
        "AdaptiveIcons.Outlined.Face" -> AdaptiveIcons.Outlined.Face
        "AdaptiveIcons.Outlined.Favorite" -> AdaptiveIcons.Outlined.Favorite
        "AdaptiveIcons.Outlined.FavoriteBorder" -> AdaptiveIcons.Outlined.FavoriteBorder
        "AdaptiveIcons.Outlined.Home" -> AdaptiveIcons.Outlined.Home
        "AdaptiveIcons.Outlined.Info" -> AdaptiveIcons.Outlined.Info
        "AdaptiveIcons.Outlined.KeyboardArrowDown" -> AdaptiveIcons.Outlined.KeyboardArrowDown
        "AdaptiveIcons.Outlined.KeyboardArrowLeft" -> AdaptiveIcons.Outlined.KeyboardArrowLeft
        "AdaptiveIcons.Outlined.KeyboardArrowRight" -> AdaptiveIcons.Outlined.KeyboardArrowRight
        "AdaptiveIcons.Outlined.KeyboardArrowUp" -> AdaptiveIcons.Outlined.KeyboardArrowUp
        "AdaptiveIcons.Outlined.List" -> AdaptiveIcons.Outlined.List
        "AdaptiveIcons.Outlined.LocationOn" -> AdaptiveIcons.Outlined.LocationOn
        "AdaptiveIcons.Outlined.Lock" -> AdaptiveIcons.Outlined.Lock
        "AdaptiveIcons.Outlined.MailOutline" -> AdaptiveIcons.Outlined.MailOutline
        "AdaptiveIcons.Outlined.Menu" -> AdaptiveIcons.Outlined.Menu
        "AdaptiveIcons.Outlined.MoreVert" -> AdaptiveIcons.Outlined.MoreVert
        "AdaptiveIcons.Outlined.Notifications" -> AdaptiveIcons.Outlined.Notifications
        "AdaptiveIcons.Outlined.Person" -> AdaptiveIcons.Outlined.Person
        "AdaptiveIcons.Outlined.Phone" -> AdaptiveIcons.Outlined.Phone
        "AdaptiveIcons.Outlined.Place" -> AdaptiveIcons.Outlined.Place
        "AdaptiveIcons.Outlined.PlayArrow" -> AdaptiveIcons.Outlined.PlayArrow
        "AdaptiveIcons.Outlined.Refresh" -> AdaptiveIcons.Outlined.Refresh
        "AdaptiveIcons.Outlined.Search" -> AdaptiveIcons.Outlined.Search
        "AdaptiveIcons.Outlined.Send" -> AdaptiveIcons.Outlined.Send
        "AdaptiveIcons.Outlined.Settings" -> AdaptiveIcons.Outlined.Settings
        "AdaptiveIcons.Outlined.Share" -> AdaptiveIcons.Outlined.Share
        "AdaptiveIcons.Outlined.ShoppingCart" -> AdaptiveIcons.Outlined.ShoppingCart
        "AdaptiveIcons.Outlined.Star" -> AdaptiveIcons.Outlined.Star
        "AdaptiveIcons.Outlined.ThumbUp" -> AdaptiveIcons.Outlined.ThumbUp
        "AdaptiveIcons.Outlined.Warning" -> AdaptiveIcons.Outlined.Warning
        "CupertinoIcons.Filled.Airtag" -> CupertinoIcons.Filled.Airtag
        "CupertinoIcons.Filled.Alarm" -> CupertinoIcons.Filled.Alarm
        "CupertinoIcons.Filled.Appletv" -> CupertinoIcons.Filled.Appletv
        "CupertinoIcons.Filled.Archivebox" -> CupertinoIcons.Filled.Archivebox
        "CupertinoIcons.Filled.ArrowClockwiseCircle" -> CupertinoIcons.Filled.ArrowClockwiseCircle
        "CupertinoIcons.Filled.ArrowCounterclockwiseCircle" -> CupertinoIcons.Filled.ArrowCounterclockwiseCircle
        "CupertinoIcons.Filled.ArrowCounterclockwiseIcloud" -> CupertinoIcons.Filled.ArrowCounterclockwiseIcloud
        "CupertinoIcons.Filled.ArrowDownCircle" -> CupertinoIcons.Filled.ArrowDownCircle
        "CupertinoIcons.Filled.ArrowDownDoc" -> CupertinoIcons.Filled.ArrowDownDoc
        "CupertinoIcons.Filled.ArrowTriangle2CirclepathCamera" -> CupertinoIcons.Filled.ArrowTriangle2CirclepathCamera
        "CupertinoIcons.Filled.ArrowTriangle2CirclepathCircle" -> CupertinoIcons.Filled.ArrowTriangle2CirclepathCircle
        "CupertinoIcons.Filled.ArrowTurnUpForwardIphone" -> CupertinoIcons.Filled.ArrowTurnUpForwardIphone
        "CupertinoIcons.Filled.ArrowUpDoc" -> CupertinoIcons.Filled.ArrowUpDoc
        "CupertinoIcons.Filled.ArrowshapeTurnUpLeft" -> CupertinoIcons.Filled.ArrowshapeTurnUpLeft
        "CupertinoIcons.Filled.ArrowshapeTurnUpLeft2" -> CupertinoIcons.Filled.ArrowshapeTurnUpLeft2
        "CupertinoIcons.Filled.Backward" -> CupertinoIcons.Filled.Backward
        "CupertinoIcons.Filled.BackwardEnd" -> CupertinoIcons.Filled.BackwardEnd
        "CupertinoIcons.Filled.Bag" -> CupertinoIcons.Filled.Bag
        "CupertinoIcons.Filled.BagBadgeMinus" -> CupertinoIcons.Filled.BagBadgeMinus
        "CupertinoIcons.Filled.BagBadgePlus" -> CupertinoIcons.Filled.BagBadgePlus
        "CupertinoIcons.Filled.Balloon" -> CupertinoIcons.Filled.Balloon
        "CupertinoIcons.Filled.Bandage" -> CupertinoIcons.Filled.Bandage
        "CupertinoIcons.Filled.Banknote" -> CupertinoIcons.Filled.Banknote
        "CupertinoIcons.Filled.Baseball" -> CupertinoIcons.Filled.Baseball
        "CupertinoIcons.Filled.Basket" -> CupertinoIcons.Filled.Basket
        "CupertinoIcons.Filled.Basketball" -> CupertinoIcons.Filled.Basketball
        "CupertinoIcons.Filled.BedDouble" -> CupertinoIcons.Filled.BedDouble
        "CupertinoIcons.Filled.Bell" -> CupertinoIcons.Filled.Bell
        "CupertinoIcons.Filled.BellAndWavesLeftAndRight" -> CupertinoIcons.Filled.BellAndWavesLeftAndRight
        "CupertinoIcons.Filled.BellBadge" -> CupertinoIcons.Filled.BellBadge
        "CupertinoIcons.Filled.BellCircle" -> CupertinoIcons.Filled.BellCircle
        "CupertinoIcons.Filled.BellSlash" -> CupertinoIcons.Filled.BellSlash
        "CupertinoIcons.Filled.Binoculars" -> CupertinoIcons.Filled.Binoculars
        "CupertinoIcons.Filled.BirthdayCake" -> CupertinoIcons.Filled.BirthdayCake
        "CupertinoIcons.Filled.Bolt" -> CupertinoIcons.Filled.Bolt
        "CupertinoIcons.Filled.BoltHorizontal" -> CupertinoIcons.Filled.BoltHorizontal
        "CupertinoIcons.Filled.BoltSlash" -> CupertinoIcons.Filled.BoltSlash
        "CupertinoIcons.Filled.Book" -> CupertinoIcons.Filled.Book
        "CupertinoIcons.Filled.BookCircle" -> CupertinoIcons.Filled.BookCircle
        "CupertinoIcons.Filled.BookClosed" -> CupertinoIcons.Filled.BookClosed
        "CupertinoIcons.Filled.Bookmark" -> CupertinoIcons.Filled.Bookmark
        "CupertinoIcons.Filled.BookmarkSlash" -> CupertinoIcons.Filled.BookmarkSlash
        "CupertinoIcons.Filled.Briefcase" -> CupertinoIcons.Filled.Briefcase
        "CupertinoIcons.Filled.BubbleLeft" -> CupertinoIcons.Filled.BubbleLeft
        "CupertinoIcons.Filled.BubbleRight" -> CupertinoIcons.Filled.BubbleRight
        "CupertinoIcons.Filled.Building" -> CupertinoIcons.Filled.Building
        "CupertinoIcons.Filled.Building2" -> CupertinoIcons.Filled.Building2
        "CupertinoIcons.Filled.Burst" -> CupertinoIcons.Filled.Burst
        "CupertinoIcons.Filled.Camera" -> CupertinoIcons.Filled.Camera
        "CupertinoIcons.Filled.CameraCircle" -> CupertinoIcons.Filled.CameraCircle
        "CupertinoIcons.Filled.Capslock" -> CupertinoIcons.Filled.Capslock
        "CupertinoIcons.Filled.Car" -> CupertinoIcons.Filled.Car
        "CupertinoIcons.Filled.Cart" -> CupertinoIcons.Filled.Cart
        "CupertinoIcons.Filled.CartBadgeMinus" -> CupertinoIcons.Filled.CartBadgeMinus
        "CupertinoIcons.Filled.CartBadgePlus" -> CupertinoIcons.Filled.CartBadgePlus
        "CupertinoIcons.Filled.Case" -> CupertinoIcons.Filled.Case
        "CupertinoIcons.Filled.ChartBar" -> CupertinoIcons.Filled.ChartBar
        "CupertinoIcons.Filled.CheckmarkCircle" -> CupertinoIcons.Filled.CheckmarkCircle
        "CupertinoIcons.Filled.CheckmarkIcloud" -> CupertinoIcons.Filled.CheckmarkIcloud
        "CupertinoIcons.Filled.CheckmarkMessage" -> CupertinoIcons.Filled.CheckmarkMessage
        "CupertinoIcons.Filled.CheckmarkSeal" -> CupertinoIcons.Filled.CheckmarkSeal
        "CupertinoIcons.Filled.CheckmarkShield" -> CupertinoIcons.Filled.CheckmarkShield
        "CupertinoIcons.Filled.CheckmarkSquare" -> CupertinoIcons.Filled.CheckmarkSquare
        "CupertinoIcons.Filled.CircleLefthalfed" -> CupertinoIcons.Filled.CircleLefthalfed
        "CupertinoIcons.Filled.CircleRighthalfed" -> CupertinoIcons.Filled.CircleRighthalfed
        "CupertinoIcons.Filled.Clear" -> CupertinoIcons.Filled.Clear
        "CupertinoIcons.Filled.Clipboard" -> CupertinoIcons.Filled.Clipboard
        "CupertinoIcons.Filled.Clock" -> CupertinoIcons.Filled.Clock
        "CupertinoIcons.Filled.Cloud" -> CupertinoIcons.Filled.Cloud
        "CupertinoIcons.Filled.Cone" -> CupertinoIcons.Filled.Cone
        "CupertinoIcons.Filled.Cpu" -> CupertinoIcons.Filled.Cpu
        "CupertinoIcons.Filled.Creditcard" -> CupertinoIcons.Filled.Creditcard
        "CupertinoIcons.Filled.Cross" -> CupertinoIcons.Filled.Cross
        "CupertinoIcons.Filled.CrossCircle" -> CupertinoIcons.Filled.CrossCircle
        "CupertinoIcons.Filled.CrossVial" -> CupertinoIcons.Filled.CrossVial
        "CupertinoIcons.Filled.Crown" -> CupertinoIcons.Filled.Crown
        "CupertinoIcons.Filled.Cube" -> CupertinoIcons.Filled.Cube
        "CupertinoIcons.Filled.CupAndSaucer" -> CupertinoIcons.Filled.CupAndSaucer
        "CupertinoIcons.Filled.DeleteLeft" -> CupertinoIcons.Filled.DeleteLeft
        "CupertinoIcons.Filled.DeleteRight" -> CupertinoIcons.Filled.DeleteRight
        "CupertinoIcons.Filled.Dice" -> CupertinoIcons.Filled.Dice
        "CupertinoIcons.Filled.Doc" -> CupertinoIcons.Filled.Doc
        "CupertinoIcons.Filled.DocBadgeArrowUp" -> CupertinoIcons.Filled.DocBadgeArrowUp
        "CupertinoIcons.Filled.DocBadgePlus" -> CupertinoIcons.Filled.DocBadgePlus
        "CupertinoIcons.Filled.DocOnDoc" -> CupertinoIcons.Filled.DocOnDoc
        "CupertinoIcons.Filled.DocPlaintext" -> CupertinoIcons.Filled.DocPlaintext
        "CupertinoIcons.Filled.DocText" -> CupertinoIcons.Filled.DocText
        "CupertinoIcons.Filled.Drop" -> CupertinoIcons.Filled.Drop
        "CupertinoIcons.Filled.Ear" -> CupertinoIcons.Filled.Ear
        "CupertinoIcons.Filled.EllipsisBubble" -> CupertinoIcons.Filled.EllipsisBubble
        "CupertinoIcons.Filled.EllipsisCircle" -> CupertinoIcons.Filled.EllipsisCircle
        "CupertinoIcons.Filled.EllipsisMessage" -> CupertinoIcons.Filled.EllipsisMessage
        "CupertinoIcons.Filled.Envelope" -> CupertinoIcons.Filled.Envelope
        "CupertinoIcons.Filled.EnvelopeBadge" -> CupertinoIcons.Filled.EnvelopeBadge
        "CupertinoIcons.Filled.EnvelopeCircle" -> CupertinoIcons.Filled.EnvelopeCircle
        "CupertinoIcons.Filled.EnvelopeOpen" -> CupertinoIcons.Filled.EnvelopeOpen
        "CupertinoIcons.Filled.Eraser" -> CupertinoIcons.Filled.Eraser
        "CupertinoIcons.Filled.ExclamationmarkCircle" -> CupertinoIcons.Filled.ExclamationmarkCircle
        "CupertinoIcons.Filled.ExclamationmarkIcloud" -> CupertinoIcons.Filled.ExclamationmarkIcloud
        "CupertinoIcons.Filled.ExclamationmarkSquare" -> CupertinoIcons.Filled.ExclamationmarkSquare
        "CupertinoIcons.Filled.ExclamationmarkTriangle" -> CupertinoIcons.Filled.ExclamationmarkTriangle
        "CupertinoIcons.Filled.Externaldrive" -> CupertinoIcons.Filled.Externaldrive
        "CupertinoIcons.Filled.Eye" -> CupertinoIcons.Filled.Eye
        "CupertinoIcons.Filled.EyeSlash" -> CupertinoIcons.Filled.EyeSlash
        "CupertinoIcons.Filled.Facemask" -> CupertinoIcons.Filled.Facemask
        "CupertinoIcons.Filled.Fanblades" -> CupertinoIcons.Filled.Fanblades
        "CupertinoIcons.Filled.FanbladesSlash" -> CupertinoIcons.Filled.FanbladesSlash
        "CupertinoIcons.Filled.Film" -> CupertinoIcons.Filled.Film
        "CupertinoIcons.Filled.Flag" -> CupertinoIcons.Filled.Flag
        "CupertinoIcons.Filled.Flag2Crossed" -> CupertinoIcons.Filled.Flag2Crossed
        "CupertinoIcons.Filled.FlagSlash" -> CupertinoIcons.Filled.FlagSlash
        "CupertinoIcons.Filled.Flame" -> CupertinoIcons.Filled.Flame
        "CupertinoIcons.Filled.Folder" -> CupertinoIcons.Filled.Folder
        "CupertinoIcons.Filled.FolderBadgePlus" -> CupertinoIcons.Filled.FolderBadgePlus
        "CupertinoIcons.Filled.Football" -> CupertinoIcons.Filled.Football
        "CupertinoIcons.Filled.ForkKnifeCircle" -> CupertinoIcons.Filled.ForkKnifeCircle
        "CupertinoIcons.Filled.Forward" -> CupertinoIcons.Filled.Forward
        "CupertinoIcons.Filled.ForwardEnd" -> CupertinoIcons.Filled.ForwardEnd
        "CupertinoIcons.Filled.Fuelpump" -> CupertinoIcons.Filled.Fuelpump
        "CupertinoIcons.Filled.Gamecontroller" -> CupertinoIcons.Filled.Gamecontroller
        "CupertinoIcons.Filled.Gearshape" -> CupertinoIcons.Filled.Gearshape
        "CupertinoIcons.Filled.Gearshape2" -> CupertinoIcons.Filled.Gearshape2
        "CupertinoIcons.Filled.Gift" -> CupertinoIcons.Filled.Gift
        "CupertinoIcons.Filled.Giftcard" -> CupertinoIcons.Filled.Giftcard
        "CupertinoIcons.Filled.GlobeDesk" -> CupertinoIcons.Filled.GlobeDesk
        "CupertinoIcons.Filled.Graduationcap" -> CupertinoIcons.Filled.Graduationcap
        "CupertinoIcons.Filled.Hammer" -> CupertinoIcons.Filled.Hammer
        "CupertinoIcons.Filled.HandDraw" -> CupertinoIcons.Filled.HandDraw
        "CupertinoIcons.Filled.HandPointUp" -> CupertinoIcons.Filled.HandPointUp
        "CupertinoIcons.Filled.HandPointUpLeft" -> CupertinoIcons.Filled.HandPointUpLeft
        "CupertinoIcons.Filled.HandRaised" -> CupertinoIcons.Filled.HandRaised
        "CupertinoIcons.Filled.HandRaisedSlash" -> CupertinoIcons.Filled.HandRaisedSlash
        "CupertinoIcons.Filled.HandTap" -> CupertinoIcons.Filled.HandTap
        "CupertinoIcons.Filled.HandThumbsdown" -> CupertinoIcons.Filled.HandThumbsdown
        "CupertinoIcons.Filled.HandThumbsup" -> CupertinoIcons.Filled.HandThumbsup
        "CupertinoIcons.Filled.HandWave" -> CupertinoIcons.Filled.HandWave
        "CupertinoIcons.Filled.HandsSparkles" -> CupertinoIcons.Filled.HandsSparkles
        "CupertinoIcons.Filled.HeadphonesCircle" -> CupertinoIcons.Filled.HeadphonesCircle
        "CupertinoIcons.Filled.Heart" -> CupertinoIcons.Filled.Heart
        "CupertinoIcons.Filled.HeartCircle" -> CupertinoIcons.Filled.HeartCircle
        "CupertinoIcons.Filled.HeartSlash" -> CupertinoIcons.Filled.HeartSlash
        "CupertinoIcons.Filled.HeartTextSquare" -> CupertinoIcons.Filled.HeartTextSquare
        "CupertinoIcons.Filled.Hifispeaker" -> CupertinoIcons.Filled.Hifispeaker
        "CupertinoIcons.Filled.Homepod" -> CupertinoIcons.Filled.Homepod
        "CupertinoIcons.Filled.Homepodmini" -> CupertinoIcons.Filled.Homepodmini
        "CupertinoIcons.Filled.House" -> CupertinoIcons.Filled.House
        "CupertinoIcons.Filled.Icloud" -> CupertinoIcons.Filled.Icloud
        "CupertinoIcons.Filled.IcloudAndArrowDown" -> CupertinoIcons.Filled.IcloudAndArrowDown
        "CupertinoIcons.Filled.IcloudAndArrowUp" -> CupertinoIcons.Filled.IcloudAndArrowUp
        "CupertinoIcons.Filled.InfoBubble" -> CupertinoIcons.Filled.InfoBubble
        "CupertinoIcons.Filled.InfoCircle" -> CupertinoIcons.Filled.InfoCircle
        "CupertinoIcons.Filled.InfoSquare" -> CupertinoIcons.Filled.InfoSquare
        "CupertinoIcons.Filled.Key" -> CupertinoIcons.Filled.Key
        "CupertinoIcons.Filled.KeyIcloud" -> CupertinoIcons.Filled.KeyIcloud
        "CupertinoIcons.Filled.Keyboard" -> CupertinoIcons.Filled.Keyboard
        "CupertinoIcons.Filled.Lanyardcard" -> CupertinoIcons.Filled.Lanyardcard
        "CupertinoIcons.Filled.Leaf" -> CupertinoIcons.Filled.Leaf
        "CupertinoIcons.Filled.Level" -> CupertinoIcons.Filled.Level
        "CupertinoIcons.Filled.Lifepreserver" -> CupertinoIcons.Filled.Lifepreserver
        "CupertinoIcons.Filled.LightBeaconMax" -> CupertinoIcons.Filled.LightBeaconMax
        "CupertinoIcons.Filled.Lightbulb" -> CupertinoIcons.Filled.Lightbulb
        "CupertinoIcons.Filled.LightbulbSlash" -> CupertinoIcons.Filled.LightbulbSlash
        "CupertinoIcons.Filled.LinkCircle" -> CupertinoIcons.Filled.LinkCircle
        "CupertinoIcons.Filled.ListBulletCircle" -> CupertinoIcons.Filled.ListBulletCircle
        "CupertinoIcons.Filled.ListBulletClipboard" -> CupertinoIcons.Filled.ListBulletClipboard
        "CupertinoIcons.Filled.ListClipboard" -> CupertinoIcons.Filled.ListClipboard
        "CupertinoIcons.Filled.Location" -> CupertinoIcons.Filled.Location
        "CupertinoIcons.Filled.Lock" -> CupertinoIcons.Filled.Lock
        "CupertinoIcons.Filled.LockCircle" -> CupertinoIcons.Filled.LockCircle
        "CupertinoIcons.Filled.LockOpen" -> CupertinoIcons.Filled.LockOpen
        "CupertinoIcons.Filled.LockSlash" -> CupertinoIcons.Filled.LockSlash
        "CupertinoIcons.Filled.Magazine" -> CupertinoIcons.Filled.Magazine
        "CupertinoIcons.Filled.Mail" -> CupertinoIcons.Filled.Mail
        "CupertinoIcons.Filled.MailStack" -> CupertinoIcons.Filled.MailStack
        "CupertinoIcons.Filled.Map" -> CupertinoIcons.Filled.Map
        "CupertinoIcons.Filled.Medal" -> CupertinoIcons.Filled.Medal
        "CupertinoIcons.Filled.Megaphone" -> CupertinoIcons.Filled.Megaphone
        "CupertinoIcons.Filled.Menucard" -> CupertinoIcons.Filled.Menucard
        "CupertinoIcons.Filled.Message" -> CupertinoIcons.Filled.Message
        "CupertinoIcons.Filled.MessageBadgeed" -> CupertinoIcons.Filled.MessageBadgeed
        "CupertinoIcons.Filled.Mic" -> CupertinoIcons.Filled.Mic
        "CupertinoIcons.Filled.MicSlash" -> CupertinoIcons.Filled.MicSlash
        "CupertinoIcons.Filled.MinusCircle" -> CupertinoIcons.Filled.MinusCircle
        "CupertinoIcons.Filled.Moon" -> CupertinoIcons.Filled.Moon
        "CupertinoIcons.Filled.MoonStars" -> CupertinoIcons.Filled.MoonStars
        "CupertinoIcons.Filled.Mount" -> CupertinoIcons.Filled.Mount
        "CupertinoIcons.Filled.Newspaper" -> CupertinoIcons.Filled.Newspaper
        "CupertinoIcons.Filled.Opticaldisc" -> CupertinoIcons.Filled.Opticaldisc
        "CupertinoIcons.Filled.Paintbrush" -> CupertinoIcons.Filled.Paintbrush
        "CupertinoIcons.Filled.PaintbrushPointed" -> CupertinoIcons.Filled.PaintbrushPointed
        "CupertinoIcons.Filled.Paintpalette" -> CupertinoIcons.Filled.Paintpalette
        "CupertinoIcons.Filled.PaperclipCircle" -> CupertinoIcons.Filled.PaperclipCircle
        "CupertinoIcons.Filled.Paperplane" -> CupertinoIcons.Filled.Paperplane
        "CupertinoIcons.Filled.PartyPopper" -> CupertinoIcons.Filled.PartyPopper
        "CupertinoIcons.Filled.Pause" -> CupertinoIcons.Filled.Pause
        "CupertinoIcons.Filled.PauseCircle" -> CupertinoIcons.Filled.PauseCircle
        "CupertinoIcons.Filled.Pawprint" -> CupertinoIcons.Filled.Pawprint
        "CupertinoIcons.Filled.PencilCircle" -> CupertinoIcons.Filled.PencilCircle
        "CupertinoIcons.Filled.Person" -> CupertinoIcons.Filled.Person
        "CupertinoIcons.Filled.Person2" -> CupertinoIcons.Filled.Person2
        "CupertinoIcons.Filled.PersonCircle" -> CupertinoIcons.Filled.PersonCircle
        "CupertinoIcons.Filled.PersonCropCircle" -> CupertinoIcons.Filled.PersonCropCircle
        "CupertinoIcons.Filled.PersonCropCircleBadgeMinus" -> CupertinoIcons.Filled.PersonCropCircleBadgeMinus
        "CupertinoIcons.Filled.PersonCropCircleBadgePlus" -> CupertinoIcons.Filled.PersonCropCircleBadgePlus
        "CupertinoIcons.Filled.PersonCropSquare" -> CupertinoIcons.Filled.PersonCropSquare
        "CupertinoIcons.Filled.PersonIcloud" -> CupertinoIcons.Filled.PersonIcloud
        "CupertinoIcons.Filled.PersonTextRectangle" -> CupertinoIcons.Filled.PersonTextRectangle
        "CupertinoIcons.Filled.PersonViewfinder" -> CupertinoIcons.Filled.PersonViewfinder
        "CupertinoIcons.Filled.PersonWave2" -> CupertinoIcons.Filled.PersonWave2
        "CupertinoIcons.Filled.Phone" -> CupertinoIcons.Filled.Phone
        "CupertinoIcons.Filled.PhoneAndWaveform" -> CupertinoIcons.Filled.PhoneAndWaveform
        "CupertinoIcons.Filled.PhoneArrowDownLeft" -> CupertinoIcons.Filled.PhoneArrowDownLeft
        "CupertinoIcons.Filled.PhoneArrowUpRight" -> CupertinoIcons.Filled.PhoneArrowUpRight
        "CupertinoIcons.Filled.PhoneBadgePlus" -> CupertinoIcons.Filled.PhoneBadgePlus
        "CupertinoIcons.Filled.PhoneCircle" -> CupertinoIcons.Filled.PhoneCircle
        "CupertinoIcons.Filled.PhoneConnection" -> CupertinoIcons.Filled.PhoneConnection
        "CupertinoIcons.Filled.Photo" -> CupertinoIcons.Filled.Photo
        "CupertinoIcons.Filled.PhotoStack" -> CupertinoIcons.Filled.PhotoStack
        "CupertinoIcons.Filled.Pill" -> CupertinoIcons.Filled.Pill
        "CupertinoIcons.Filled.Pin" -> CupertinoIcons.Filled.Pin
        "CupertinoIcons.Filled.PinCircle" -> CupertinoIcons.Filled.PinCircle
        "CupertinoIcons.Filled.PinSlash" -> CupertinoIcons.Filled.PinSlash
        "CupertinoIcons.Filled.Pip" -> CupertinoIcons.Filled.Pip
        "CupertinoIcons.Filled.Play" -> CupertinoIcons.Filled.Play
        "CupertinoIcons.Filled.PlayCircle" -> CupertinoIcons.Filled.PlayCircle
        "CupertinoIcons.Filled.PlusApp" -> CupertinoIcons.Filled.PlusApp
        "CupertinoIcons.Filled.PlusBubble" -> CupertinoIcons.Filled.PlusBubble
        "CupertinoIcons.Filled.PlusCircle" -> CupertinoIcons.Filled.PlusCircle
        "CupertinoIcons.Filled.PlusMessage" -> CupertinoIcons.Filled.PlusMessage
        "CupertinoIcons.Filled.PlusSquare" -> CupertinoIcons.Filled.PlusSquare
        "CupertinoIcons.Filled.Popcorn" -> CupertinoIcons.Filled.Popcorn
        "CupertinoIcons.Filled.PowerCircle" -> CupertinoIcons.Filled.PowerCircle
        "CupertinoIcons.Filled.Printer" -> CupertinoIcons.Filled.Printer
        "CupertinoIcons.Filled.Puzzlepiece" -> CupertinoIcons.Filled.Puzzlepiece
        "CupertinoIcons.Filled.PuzzlepieceExtension" -> CupertinoIcons.Filled.PuzzlepieceExtension
        "CupertinoIcons.Filled.QuestionmarkApp" -> CupertinoIcons.Filled.QuestionmarkApp
        "CupertinoIcons.Filled.QuestionmarkCircle" -> CupertinoIcons.Filled.QuestionmarkCircle
        "CupertinoIcons.Filled.QuestionmarkFolder" -> CupertinoIcons.Filled.QuestionmarkFolder
        "CupertinoIcons.Filled.QuestionmarkSquare" -> CupertinoIcons.Filled.QuestionmarkSquare
        "CupertinoIcons.Filled.RecordCircle" -> CupertinoIcons.Filled.RecordCircle
        "CupertinoIcons.Filled.RectanglePortraitAndArrowForward" -> CupertinoIcons.Filled.RectanglePortraitAndArrowForward
        "CupertinoIcons.Filled.RectangleStack" -> CupertinoIcons.Filled.RectangleStack
        "CupertinoIcons.Filled.RotateLeft" -> CupertinoIcons.Filled.RotateLeft
        "CupertinoIcons.Filled.RotateRight" -> CupertinoIcons.Filled.RotateRight
        "CupertinoIcons.Filled.Ruler" -> CupertinoIcons.Filled.Ruler
        "CupertinoIcons.Filled.Safari" -> CupertinoIcons.Filled.Safari
        "CupertinoIcons.Filled.Scalemass" -> CupertinoIcons.Filled.Scalemass
        "CupertinoIcons.Filled.Scroll" -> CupertinoIcons.Filled.Scroll
        "CupertinoIcons.Filled.ShazamLogo" -> CupertinoIcons.Filled.ShazamLogo
        "CupertinoIcons.Filled.Shield" -> CupertinoIcons.Filled.Shield
        "CupertinoIcons.Filled.ShieldLefthalfed" -> CupertinoIcons.Filled.ShieldLefthalfed
        "CupertinoIcons.Filled.ShieldRighthalfed" -> CupertinoIcons.Filled.ShieldRighthalfed
        "CupertinoIcons.Filled.ShieldSlash" -> CupertinoIcons.Filled.ShieldSlash
        "CupertinoIcons.Filled.Shippingbox" -> CupertinoIcons.Filled.Shippingbox
        "CupertinoIcons.Filled.Shoeprints" -> CupertinoIcons.Filled.Shoeprints
        "CupertinoIcons.Filled.Simcard" -> CupertinoIcons.Filled.Simcard
        "CupertinoIcons.Filled.SmallcircleedCircle" -> CupertinoIcons.Filled.SmallcircleedCircle
        "CupertinoIcons.Filled.Speaker" -> CupertinoIcons.Filled.Speaker
        "CupertinoIcons.Filled.SpeakerMinus" -> CupertinoIcons.Filled.SpeakerMinus
        "CupertinoIcons.Filled.SpeakerPlus" -> CupertinoIcons.Filled.SpeakerPlus
        "CupertinoIcons.Filled.SpeakerSlash" -> CupertinoIcons.Filled.SpeakerSlash
        "CupertinoIcons.Filled.SpeakerWave2" -> CupertinoIcons.Filled.SpeakerWave2
        "CupertinoIcons.Filled.SquareAndArrowUp" -> CupertinoIcons.Filled.SquareAndArrowUp
        "CupertinoIcons.Filled.SquareBottomthirdInseted" -> CupertinoIcons.Filled.SquareBottomthirdInseted
        "CupertinoIcons.Filled.SquareOnSquare" -> CupertinoIcons.Filled.SquareOnSquare
        "CupertinoIcons.Filled.SquareSplit1x2" -> CupertinoIcons.Filled.SquareSplit1x2
        "CupertinoIcons.Filled.SquareSplit2x1" -> CupertinoIcons.Filled.SquareSplit2x1
        "CupertinoIcons.Filled.SquareStack" -> CupertinoIcons.Filled.SquareStack
        "CupertinoIcons.Filled.SquareStack3dUp" -> CupertinoIcons.Filled.SquareStack3dUp
        "CupertinoIcons.Filled.SquareTopthirdInseted" -> CupertinoIcons.Filled.SquareTopthirdInseted
        "CupertinoIcons.Filled.Star" -> CupertinoIcons.Filled.Star
        "CupertinoIcons.Filled.StarLeadinghalfed" -> CupertinoIcons.Filled.StarLeadinghalfed
        "CupertinoIcons.Filled.StarSlash" -> CupertinoIcons.Filled.StarSlash
        "CupertinoIcons.Filled.Staroflife" -> CupertinoIcons.Filled.Staroflife
        "CupertinoIcons.Filled.Stop" -> CupertinoIcons.Filled.Stop
        "CupertinoIcons.Filled.StopCircle" -> CupertinoIcons.Filled.StopCircle
        "CupertinoIcons.Filled.Suitcase" -> CupertinoIcons.Filled.Suitcase
        "CupertinoIcons.Filled.SunMax" -> CupertinoIcons.Filled.SunMax
        "CupertinoIcons.Filled.Tag" -> CupertinoIcons.Filled.Tag
        "CupertinoIcons.Filled.Terminal" -> CupertinoIcons.Filled.Terminal
        "CupertinoIcons.Filled.TextBubble" -> CupertinoIcons.Filled.TextBubble
        "CupertinoIcons.Filled.Theatermasks" -> CupertinoIcons.Filled.Theatermasks
        "CupertinoIcons.Filled.Trash" -> CupertinoIcons.Filled.Trash
        "CupertinoIcons.Filled.TrashSlash" -> CupertinoIcons.Filled.TrashSlash
        "CupertinoIcons.Filled.TrayAndArrowDown" -> CupertinoIcons.Filled.TrayAndArrowDown
        "CupertinoIcons.Filled.TrayAndArrowUp" -> CupertinoIcons.Filled.TrayAndArrowUp
        "CupertinoIcons.Filled.Trophy" -> CupertinoIcons.Filled.Trophy
        "CupertinoIcons.Filled.Tshirt" -> CupertinoIcons.Filled.Tshirt
        "CupertinoIcons.Filled.Tv" -> CupertinoIcons.Filled.Tv
        "CupertinoIcons.Filled.TvAndHifispeaker" -> CupertinoIcons.Filled.TvAndHifispeaker
        "CupertinoIcons.Filled.Umbrella" -> CupertinoIcons.Filled.Umbrella
        "CupertinoIcons.Filled.Video" -> CupertinoIcons.Filled.Video
        "CupertinoIcons.Filled.VideoCircle" -> CupertinoIcons.Filled.VideoCircle
        "CupertinoIcons.Filled.VideoSlash" -> CupertinoIcons.Filled.VideoSlash
        "CupertinoIcons.Filled.Volleyball" -> CupertinoIcons.Filled.Volleyball
        "CupertinoIcons.Filled.WalletPass" -> CupertinoIcons.Filled.WalletPass
        "CupertinoIcons.Filled.WebCamera" -> CupertinoIcons.Filled.WebCamera
        "CupertinoIcons.Filled.WifiRouter" -> CupertinoIcons.Filled.WifiRouter
        "CupertinoIcons.Filled.Wineglass" -> CupertinoIcons.Filled.Wineglass
        "CupertinoIcons.Filled.XmarkApp" -> CupertinoIcons.Filled.XmarkApp
        "CupertinoIcons.Filled.XmarkBin" -> CupertinoIcons.Filled.XmarkBin
        "CupertinoIcons.Filled.XmarkCircle" -> CupertinoIcons.Filled.XmarkCircle
        "CupertinoIcons.Filled.XmarkIcloud" -> CupertinoIcons.Filled.XmarkIcloud
        "CupertinoIcons.Filled.XmarkSeal" -> CupertinoIcons.Filled.XmarkSeal
        "CupertinoIcons.Filled.XmarkShield" -> CupertinoIcons.Filled.XmarkShield
        "CupertinoIcons.Filled._4kTv" -> CupertinoIcons.Filled._4kTv
        "CupertinoIcons.Outlined.Airplane" -> CupertinoIcons.Outlined.Airplane
        "CupertinoIcons.Outlined.AirplaneArrival" -> CupertinoIcons.Outlined.AirplaneArrival
        "CupertinoIcons.Outlined.AirplaneDeparture" -> CupertinoIcons.Outlined.AirplaneDeparture
        "CupertinoIcons.Outlined.Airplayaudio" -> CupertinoIcons.Outlined.Airplayaudio
        "CupertinoIcons.Outlined.Airpods" -> CupertinoIcons.Outlined.Airpods
        "CupertinoIcons.Outlined.AirpodsGen3" -> CupertinoIcons.Outlined.AirpodsGen3
        "CupertinoIcons.Outlined.Airpodsmax" -> CupertinoIcons.Outlined.Airpodsmax
        "CupertinoIcons.Outlined.Airpodspro" -> CupertinoIcons.Outlined.Airpodspro
        "CupertinoIcons.Outlined.Airtag" -> CupertinoIcons.Outlined.Airtag
        "CupertinoIcons.Outlined.Alarm" -> CupertinoIcons.Outlined.Alarm
        "CupertinoIcons.Outlined.Alt" -> CupertinoIcons.Outlined.Alt
        "CupertinoIcons.Outlined.Angle" -> CupertinoIcons.Outlined.Angle
        "CupertinoIcons.Outlined.AntennaRadiowavesLeftAndRight" -> CupertinoIcons.Outlined.AntennaRadiowavesLeftAndRight
        "CupertinoIcons.Outlined.AntennaRadiowavesLeftAndRightSlash" -> CupertinoIcons.Outlined.AntennaRadiowavesLeftAndRightSlash
        "CupertinoIcons.Outlined.AppleLogo" -> CupertinoIcons.Outlined.AppleLogo
        "CupertinoIcons.Outlined.Applepencil" -> CupertinoIcons.Outlined.Applepencil
        "CupertinoIcons.Outlined.Appletv" -> CupertinoIcons.Outlined.Appletv
        "CupertinoIcons.Outlined.Applewatch" -> CupertinoIcons.Outlined.Applewatch
        "CupertinoIcons.Outlined.ApplewatchRadiowavesLeftAndRight" -> CupertinoIcons.Outlined.ApplewatchRadiowavesLeftAndRight
        "CupertinoIcons.Outlined.ApplewatchWatchface" -> CupertinoIcons.Outlined.ApplewatchWatchface
        "CupertinoIcons.Outlined.Archivebox" -> CupertinoIcons.Outlined.Archivebox
        "CupertinoIcons.Outlined.Arrow3Trianglepath" -> CupertinoIcons.Outlined.Arrow3Trianglepath
        "CupertinoIcons.Outlined.ArrowClockwise" -> CupertinoIcons.Outlined.ArrowClockwise
        "CupertinoIcons.Outlined.ArrowCounterclockwise" -> CupertinoIcons.Outlined.ArrowCounterclockwise
        "CupertinoIcons.Outlined.ArrowCounterclockwiseIcloud" -> CupertinoIcons.Outlined.ArrowCounterclockwiseIcloud
        "CupertinoIcons.Outlined.ArrowDown" -> CupertinoIcons.Outlined.ArrowDown
        "CupertinoIcons.Outlined.ArrowDownAndLineHorizontalAndArrowUp" -> CupertinoIcons.Outlined.ArrowDownAndLineHorizontalAndArrowUp
        "CupertinoIcons.Outlined.ArrowDownCircle" -> CupertinoIcons.Outlined.ArrowDownCircle
        "CupertinoIcons.Outlined.ArrowDownDoc" -> CupertinoIcons.Outlined.ArrowDownDoc
        "CupertinoIcons.Outlined.ArrowDownRightAndArrowUpLeft" -> CupertinoIcons.Outlined.ArrowDownRightAndArrowUpLeft
        "CupertinoIcons.Outlined.ArrowDownToLine" -> CupertinoIcons.Outlined.ArrowDownToLine
        "CupertinoIcons.Outlined.ArrowLeftAndRight" -> CupertinoIcons.Outlined.ArrowLeftAndRight
        "CupertinoIcons.Outlined.ArrowLeftArrowRight" -> CupertinoIcons.Outlined.ArrowLeftArrowRight
        "CupertinoIcons.Outlined.ArrowTriangle2Circlepath" -> CupertinoIcons.Outlined.ArrowTriangle2Circlepath
        "CupertinoIcons.Outlined.ArrowTriangle2CirclepathCamera" -> CupertinoIcons.Outlined.ArrowTriangle2CirclepathCamera
        "CupertinoIcons.Outlined.ArrowTriangleBranch" -> CupertinoIcons.Outlined.ArrowTriangleBranch
        "CupertinoIcons.Outlined.ArrowTurnDownLeft" -> CupertinoIcons.Outlined.ArrowTurnDownLeft
        "CupertinoIcons.Outlined.ArrowTurnDownRight" -> CupertinoIcons.Outlined.ArrowTurnDownRight
        "CupertinoIcons.Outlined.ArrowTurnRightUp" -> CupertinoIcons.Outlined.ArrowTurnRightUp
        "CupertinoIcons.Outlined.ArrowTurnUpForwardIphone" -> CupertinoIcons.Outlined.ArrowTurnUpForwardIphone
        "CupertinoIcons.Outlined.ArrowTurnUpLeft" -> CupertinoIcons.Outlined.ArrowTurnUpLeft
        "CupertinoIcons.Outlined.ArrowTurnUpRight" -> CupertinoIcons.Outlined.ArrowTurnUpRight
        "CupertinoIcons.Outlined.ArrowUpAndDown" -> CupertinoIcons.Outlined.ArrowUpAndDown
        "CupertinoIcons.Outlined.ArrowUpArrowDown" -> CupertinoIcons.Outlined.ArrowUpArrowDown
        "CupertinoIcons.Outlined.ArrowUpDoc" -> CupertinoIcons.Outlined.ArrowUpDoc
        "CupertinoIcons.Outlined.ArrowUpLeftAndArrowDownRight" -> CupertinoIcons.Outlined.ArrowUpLeftAndArrowDownRight
        "CupertinoIcons.Outlined.ArrowUturnLeft" -> CupertinoIcons.Outlined.ArrowUturnLeft
        "CupertinoIcons.Outlined.ArrowUturnRight" -> CupertinoIcons.Outlined.ArrowUturnRight
        "CupertinoIcons.Outlined.ArrowshapeTurnUpLeft" -> CupertinoIcons.Outlined.ArrowshapeTurnUpLeft
        "CupertinoIcons.Outlined.ArrowshapeTurnUpLeft2" -> CupertinoIcons.Outlined.ArrowshapeTurnUpLeft2
        "CupertinoIcons.Outlined.At" -> CupertinoIcons.Outlined.At
        "CupertinoIcons.Outlined.Backward" -> CupertinoIcons.Outlined.Backward
        "CupertinoIcons.Outlined.BackwardEnd" -> CupertinoIcons.Outlined.BackwardEnd
        "CupertinoIcons.Outlined.Bag" -> CupertinoIcons.Outlined.Bag
        "CupertinoIcons.Outlined.BagBadgeMinus" -> CupertinoIcons.Outlined.BagBadgeMinus
        "CupertinoIcons.Outlined.BagBadgePlus" -> CupertinoIcons.Outlined.BagBadgePlus
        "CupertinoIcons.Outlined.Balloon" -> CupertinoIcons.Outlined.Balloon
        "CupertinoIcons.Outlined.Bandage" -> CupertinoIcons.Outlined.Bandage
        "CupertinoIcons.Outlined.Banknote" -> CupertinoIcons.Outlined.Banknote
        "CupertinoIcons.Outlined.Barcode" -> CupertinoIcons.Outlined.Barcode
        "CupertinoIcons.Outlined.BarcodeViewfinder" -> CupertinoIcons.Outlined.BarcodeViewfinder
        "CupertinoIcons.Outlined.Baseball" -> CupertinoIcons.Outlined.Baseball
        "CupertinoIcons.Outlined.Basket" -> CupertinoIcons.Outlined.Basket
        "CupertinoIcons.Outlined.Basketball" -> CupertinoIcons.Outlined.Basketball
        "CupertinoIcons.Outlined.Battery100" -> CupertinoIcons.Outlined.Battery100
        "CupertinoIcons.Outlined.BedDouble" -> CupertinoIcons.Outlined.BedDouble
        "CupertinoIcons.Outlined.Bell" -> CupertinoIcons.Outlined.Bell
        "CupertinoIcons.Outlined.BellAndWavesLeftAndRight" -> CupertinoIcons.Outlined.BellAndWavesLeftAndRight
        "CupertinoIcons.Outlined.BellBadge" -> CupertinoIcons.Outlined.BellBadge
        "CupertinoIcons.Outlined.BellCircle" -> CupertinoIcons.Outlined.BellCircle
        "CupertinoIcons.Outlined.BellSlash" -> CupertinoIcons.Outlined.BellSlash
        "CupertinoIcons.Outlined.Bicycle" -> CupertinoIcons.Outlined.Bicycle
        "CupertinoIcons.Outlined.Binoculars" -> CupertinoIcons.Outlined.Binoculars
        "CupertinoIcons.Outlined.BirthdayCake" -> CupertinoIcons.Outlined.BirthdayCake
        "CupertinoIcons.Outlined.Bitcoinsign" -> CupertinoIcons.Outlined.Bitcoinsign
        "CupertinoIcons.Outlined.Bolt" -> CupertinoIcons.Outlined.Bolt
        "CupertinoIcons.Outlined.BoltHorizontal" -> CupertinoIcons.Outlined.BoltHorizontal
        "CupertinoIcons.Outlined.BoltSlash" -> CupertinoIcons.Outlined.BoltSlash
        "CupertinoIcons.Outlined.Book" -> CupertinoIcons.Outlined.Book
        "CupertinoIcons.Outlined.BookCircle" -> CupertinoIcons.Outlined.BookCircle
        "CupertinoIcons.Outlined.BookClosed" -> CupertinoIcons.Outlined.BookClosed
        "CupertinoIcons.Outlined.Bookmark" -> CupertinoIcons.Outlined.Bookmark
        "CupertinoIcons.Outlined.BookmarkSlash" -> CupertinoIcons.Outlined.BookmarkSlash
        "CupertinoIcons.Outlined.Brain" -> CupertinoIcons.Outlined.Brain
        "CupertinoIcons.Outlined.BrainHeadProfile" -> CupertinoIcons.Outlined.BrainHeadProfile
        "CupertinoIcons.Outlined.Briefcase" -> CupertinoIcons.Outlined.Briefcase
        "CupertinoIcons.Outlined.BubbleLeft" -> CupertinoIcons.Outlined.BubbleLeft
        "CupertinoIcons.Outlined.BubbleRight" -> CupertinoIcons.Outlined.BubbleRight
        "CupertinoIcons.Outlined.Building" -> CupertinoIcons.Outlined.Building
        "CupertinoIcons.Outlined.Building2" -> CupertinoIcons.Outlined.Building2
        "CupertinoIcons.Outlined.Burn" -> CupertinoIcons.Outlined.Burn
        "CupertinoIcons.Outlined.Burst" -> CupertinoIcons.Outlined.Burst
        "CupertinoIcons.Outlined.CableConnector" -> CupertinoIcons.Outlined.CableConnector
        "CupertinoIcons.Outlined.CableConnectorHorizontal" -> CupertinoIcons.Outlined.CableConnectorHorizontal
        "CupertinoIcons.Outlined.Calendar" -> CupertinoIcons.Outlined.Calendar
        "CupertinoIcons.Outlined.CalendarBadgePlus" -> CupertinoIcons.Outlined.CalendarBadgePlus
        "CupertinoIcons.Outlined.Camera" -> CupertinoIcons.Outlined.Camera
        "CupertinoIcons.Outlined.CameraCircle" -> CupertinoIcons.Outlined.CameraCircle
        "CupertinoIcons.Outlined.CameraFilters" -> CupertinoIcons.Outlined.CameraFilters
        "CupertinoIcons.Outlined.CameraViewfinder" -> CupertinoIcons.Outlined.CameraViewfinder
        "CupertinoIcons.Outlined.Candybarphone" -> CupertinoIcons.Outlined.Candybarphone
        "CupertinoIcons.Outlined.Capslock" -> CupertinoIcons.Outlined.Capslock
        "CupertinoIcons.Outlined.Car" -> CupertinoIcons.Outlined.Car
        "CupertinoIcons.Outlined.Cart" -> CupertinoIcons.Outlined.Cart
        "CupertinoIcons.Outlined.CartBadgeMinus" -> CupertinoIcons.Outlined.CartBadgeMinus
        "CupertinoIcons.Outlined.CartBadgePlus" -> CupertinoIcons.Outlined.CartBadgePlus
        "CupertinoIcons.Outlined.Case" -> CupertinoIcons.Outlined.Case
        "CupertinoIcons.Outlined.Centsign" -> CupertinoIcons.Outlined.Centsign
        "CupertinoIcons.Outlined.Character" -> CupertinoIcons.Outlined.Character
        "CupertinoIcons.Outlined.ChartBar" -> CupertinoIcons.Outlined.ChartBar
        "CupertinoIcons.Outlined.ChartLineDowntrendXyaxis" -> CupertinoIcons.Outlined.ChartLineDowntrendXyaxis
        "CupertinoIcons.Outlined.ChartLineUptrendXyaxis" -> CupertinoIcons.Outlined.ChartLineUptrendXyaxis
        "CupertinoIcons.Outlined.CheckerboardShield" -> CupertinoIcons.Outlined.CheckerboardShield
        "CupertinoIcons.Outlined.Checklist" -> CupertinoIcons.Outlined.Checklist
        "CupertinoIcons.Outlined.ChecklistChecked" -> CupertinoIcons.Outlined.ChecklistChecked
        "CupertinoIcons.Outlined.ChecklistUnchecked" -> CupertinoIcons.Outlined.ChecklistUnchecked
        "CupertinoIcons.Outlined.Checkmark" -> CupertinoIcons.Outlined.Checkmark
        "CupertinoIcons.Outlined.CheckmarkCircle" -> CupertinoIcons.Outlined.CheckmarkCircle
        "CupertinoIcons.Outlined.CheckmarkIcloud" -> CupertinoIcons.Outlined.CheckmarkIcloud
        "CupertinoIcons.Outlined.CheckmarkMessage" -> CupertinoIcons.Outlined.CheckmarkMessage
        "CupertinoIcons.Outlined.CheckmarkSeal" -> CupertinoIcons.Outlined.CheckmarkSeal
        "CupertinoIcons.Outlined.CheckmarkShield" -> CupertinoIcons.Outlined.CheckmarkShield
        "CupertinoIcons.Outlined.CheckmarkSquare" -> CupertinoIcons.Outlined.CheckmarkSquare
        "CupertinoIcons.Outlined.ChevronBackward" -> CupertinoIcons.Outlined.ChevronBackward
        "CupertinoIcons.Outlined.ChevronDown" -> CupertinoIcons.Outlined.ChevronDown
        "CupertinoIcons.Outlined.ChevronForward" -> CupertinoIcons.Outlined.ChevronForward
        "CupertinoIcons.Outlined.ChevronLeftForwardslashChevronRight" -> CupertinoIcons.Outlined.ChevronLeftForwardslashChevronRight
        "CupertinoIcons.Outlined.ChevronUp" -> CupertinoIcons.Outlined.ChevronUp
        "CupertinoIcons.Outlined.Clear" -> CupertinoIcons.Outlined.Clear
        "CupertinoIcons.Outlined.Clipboard" -> CupertinoIcons.Outlined.Clipboard
        "CupertinoIcons.Outlined.Clock" -> CupertinoIcons.Outlined.Clock
        "CupertinoIcons.Outlined.ClockArrowCirclepath" -> CupertinoIcons.Outlined.ClockArrowCirclepath
        "CupertinoIcons.Outlined.Cloud" -> CupertinoIcons.Outlined.Cloud
        "CupertinoIcons.Outlined.Command" -> CupertinoIcons.Outlined.Command
        "CupertinoIcons.Outlined.CompassDrawing" -> CupertinoIcons.Outlined.CompassDrawing
        "CupertinoIcons.Outlined.Cone" -> CupertinoIcons.Outlined.Cone
        "CupertinoIcons.Outlined.Cpu" -> CupertinoIcons.Outlined.Cpu
        "CupertinoIcons.Outlined.Creditcard" -> CupertinoIcons.Outlined.Creditcard
        "CupertinoIcons.Outlined.CreditcardTrianglebadgeExclamationmark" -> CupertinoIcons.Outlined.CreditcardTrianglebadgeExclamationmark
        "CupertinoIcons.Outlined.Crop" -> CupertinoIcons.Outlined.Crop
        "CupertinoIcons.Outlined.CropRotate" -> CupertinoIcons.Outlined.CropRotate
        "CupertinoIcons.Outlined.Cross" -> CupertinoIcons.Outlined.Cross
        "CupertinoIcons.Outlined.CrossCircle" -> CupertinoIcons.Outlined.CrossCircle
        "CupertinoIcons.Outlined.CrossVial" -> CupertinoIcons.Outlined.CrossVial
        "CupertinoIcons.Outlined.Crown" -> CupertinoIcons.Outlined.Crown
        "CupertinoIcons.Outlined.Cube" -> CupertinoIcons.Outlined.Cube
        "CupertinoIcons.Outlined.CupAndSaucer" -> CupertinoIcons.Outlined.CupAndSaucer
        "CupertinoIcons.Outlined.Curlybraces" -> CupertinoIcons.Outlined.Curlybraces
        "CupertinoIcons.Outlined.CursorarrowRays" -> CupertinoIcons.Outlined.CursorarrowRays
        "CupertinoIcons.Outlined.DeleteLeft" -> CupertinoIcons.Outlined.DeleteLeft
        "CupertinoIcons.Outlined.DeleteRight" -> CupertinoIcons.Outlined.DeleteRight
        "CupertinoIcons.Outlined.Desktopcomputer" -> CupertinoIcons.Outlined.Desktopcomputer
        "CupertinoIcons.Outlined.Dice" -> CupertinoIcons.Outlined.Dice
        "CupertinoIcons.Outlined.Display" -> CupertinoIcons.Outlined.Display
        "CupertinoIcons.Outlined.Divide" -> CupertinoIcons.Outlined.Divide
        "CupertinoIcons.Outlined.Doc" -> CupertinoIcons.Outlined.Doc
        "CupertinoIcons.Outlined.DocBadgeArrowUp" -> CupertinoIcons.Outlined.DocBadgeArrowUp
        "CupertinoIcons.Outlined.DocBadgePlus" -> CupertinoIcons.Outlined.DocBadgePlus
        "CupertinoIcons.Outlined.DocOnDoc" -> CupertinoIcons.Outlined.DocOnDoc
        "CupertinoIcons.Outlined.DocPlaintext" -> CupertinoIcons.Outlined.DocPlaintext
        "CupertinoIcons.Outlined.DocText" -> CupertinoIcons.Outlined.DocText
        "CupertinoIcons.Outlined.DocTextMagnifyingglass" -> CupertinoIcons.Outlined.DocTextMagnifyingglass
        "CupertinoIcons.Outlined.Dollarsign" -> CupertinoIcons.Outlined.Dollarsign
        "CupertinoIcons.Outlined.DollarsignArrowCirclepath" -> CupertinoIcons.Outlined.DollarsignArrowCirclepath
        "CupertinoIcons.Outlined.DoorLeftHandClosed" -> CupertinoIcons.Outlined.DoorLeftHandClosed
        "CupertinoIcons.Outlined.DoorLeftHandOpen" -> CupertinoIcons.Outlined.DoorLeftHandOpen
        "CupertinoIcons.Outlined.DotRadiowavesLeftAndRight" -> CupertinoIcons.Outlined.DotRadiowavesLeftAndRight
        "CupertinoIcons.Outlined.DotRadiowavesUpForward" -> CupertinoIcons.Outlined.DotRadiowavesUpForward
        "CupertinoIcons.Outlined.Drop" -> CupertinoIcons.Outlined.Drop
        "CupertinoIcons.Outlined.Ear" -> CupertinoIcons.Outlined.Ear
        "CupertinoIcons.Outlined.Earpods" -> CupertinoIcons.Outlined.Earpods
        "CupertinoIcons.Outlined.Ellipsis" -> CupertinoIcons.Outlined.Ellipsis
        "CupertinoIcons.Outlined.EllipsisBubble" -> CupertinoIcons.Outlined.EllipsisBubble
        "CupertinoIcons.Outlined.EllipsisCircle" -> CupertinoIcons.Outlined.EllipsisCircle
        "CupertinoIcons.Outlined.EllipsisCurlybraces" -> CupertinoIcons.Outlined.EllipsisCurlybraces
        "CupertinoIcons.Outlined.EllipsisMessage" -> CupertinoIcons.Outlined.EllipsisMessage
        "CupertinoIcons.Outlined.Envelope" -> CupertinoIcons.Outlined.Envelope
        "CupertinoIcons.Outlined.EnvelopeBadge" -> CupertinoIcons.Outlined.EnvelopeBadge
        "CupertinoIcons.Outlined.EnvelopeCircle" -> CupertinoIcons.Outlined.EnvelopeCircle
        "CupertinoIcons.Outlined.EnvelopeOpen" -> CupertinoIcons.Outlined.EnvelopeOpen
        "CupertinoIcons.Outlined.Eraser" -> CupertinoIcons.Outlined.Eraser
        "CupertinoIcons.Outlined.Eurosign" -> CupertinoIcons.Outlined.Eurosign
        "CupertinoIcons.Outlined.Exclamationmark" -> CupertinoIcons.Outlined.Exclamationmark
        "CupertinoIcons.Outlined.Exclamationmark2" -> CupertinoIcons.Outlined.Exclamationmark2
        "CupertinoIcons.Outlined.Exclamationmark3" -> CupertinoIcons.Outlined.Exclamationmark3
        "CupertinoIcons.Outlined.ExclamationmarkArrowTriangle2Circlepath" -> CupertinoIcons.Outlined.ExclamationmarkArrowTriangle2Circlepath
        "CupertinoIcons.Outlined.ExclamationmarkCircle" -> CupertinoIcons.Outlined.ExclamationmarkCircle
        "CupertinoIcons.Outlined.ExclamationmarkIcloud" -> CupertinoIcons.Outlined.ExclamationmarkIcloud
        "CupertinoIcons.Outlined.ExclamationmarkSquare" -> CupertinoIcons.Outlined.ExclamationmarkSquare
        "CupertinoIcons.Outlined.ExclamationmarkTriangle" -> CupertinoIcons.Outlined.ExclamationmarkTriangle
        "CupertinoIcons.Outlined.Externaldrive" -> CupertinoIcons.Outlined.Externaldrive
        "CupertinoIcons.Outlined.Eye" -> CupertinoIcons.Outlined.Eye
        "CupertinoIcons.Outlined.EyeSlash" -> CupertinoIcons.Outlined.EyeSlash
        "CupertinoIcons.Outlined.Eyebrow" -> CupertinoIcons.Outlined.Eyebrow
        "CupertinoIcons.Outlined.Eyedropper" -> CupertinoIcons.Outlined.Eyedropper
        "CupertinoIcons.Outlined.Eyeglasses" -> CupertinoIcons.Outlined.Eyeglasses
        "CupertinoIcons.Outlined.Eyes" -> CupertinoIcons.Outlined.Eyes
        "CupertinoIcons.Outlined.FaceSmiling" -> CupertinoIcons.Outlined.FaceSmiling
        "CupertinoIcons.Outlined.FaceSmilingInverse" -> CupertinoIcons.Outlined.FaceSmilingInverse
        "CupertinoIcons.Outlined.Faceid" -> CupertinoIcons.Outlined.Faceid
        "CupertinoIcons.Outlined.Facemask" -> CupertinoIcons.Outlined.Facemask
        "CupertinoIcons.Outlined.Fanblades" -> CupertinoIcons.Outlined.Fanblades
        "CupertinoIcons.Outlined.FanbladesSlash" -> CupertinoIcons.Outlined.FanbladesSlash
        "CupertinoIcons.Outlined.Fibrechannel" -> CupertinoIcons.Outlined.Fibrechannel
        "CupertinoIcons.Outlined.FigureStand" -> CupertinoIcons.Outlined.FigureStand
        "CupertinoIcons.Outlined.FigureWalk" -> CupertinoIcons.Outlined.FigureWalk
        "CupertinoIcons.Outlined.Film" -> CupertinoIcons.Outlined.Film
        "CupertinoIcons.Outlined.Flag" -> CupertinoIcons.Outlined.Flag
        "CupertinoIcons.Outlined.Flag2Crossed" -> CupertinoIcons.Outlined.Flag2Crossed
        "CupertinoIcons.Outlined.FlagCheckered2Crossed" -> CupertinoIcons.Outlined.FlagCheckered2Crossed
        "CupertinoIcons.Outlined.FlagSlash" -> CupertinoIcons.Outlined.FlagSlash
        "CupertinoIcons.Outlined.Flame" -> CupertinoIcons.Outlined.Flame
        "CupertinoIcons.Outlined.Flowchart" -> CupertinoIcons.Outlined.Flowchart
        "CupertinoIcons.Outlined.Folder" -> CupertinoIcons.Outlined.Folder
        "CupertinoIcons.Outlined.FolderBadgePlus" -> CupertinoIcons.Outlined.FolderBadgePlus
        "CupertinoIcons.Outlined.Football" -> CupertinoIcons.Outlined.Football
        "CupertinoIcons.Outlined.ForkKnife" -> CupertinoIcons.Outlined.ForkKnife
        "CupertinoIcons.Outlined.ForkKnifeCircle" -> CupertinoIcons.Outlined.ForkKnifeCircle
        "CupertinoIcons.Outlined.Forward" -> CupertinoIcons.Outlined.Forward
        "CupertinoIcons.Outlined.ForwardEnd" -> CupertinoIcons.Outlined.ForwardEnd
        "CupertinoIcons.Outlined.Francsign" -> CupertinoIcons.Outlined.Francsign
        "CupertinoIcons.Outlined.Fuelpump" -> CupertinoIcons.Outlined.Fuelpump
        "CupertinoIcons.Outlined.Gamecontroller" -> CupertinoIcons.Outlined.Gamecontroller
        "CupertinoIcons.Outlined.Gear" -> CupertinoIcons.Outlined.Gear
        "CupertinoIcons.Outlined.Gearshape" -> CupertinoIcons.Outlined.Gearshape
        "CupertinoIcons.Outlined.Gearshape2" -> CupertinoIcons.Outlined.Gearshape2
        "CupertinoIcons.Outlined.Gift" -> CupertinoIcons.Outlined.Gift
        "CupertinoIcons.Outlined.Giftcard" -> CupertinoIcons.Outlined.Giftcard
        "CupertinoIcons.Outlined.GlobeDesk" -> CupertinoIcons.Outlined.GlobeDesk
        "CupertinoIcons.Outlined.Gobackward" -> CupertinoIcons.Outlined.Gobackward
        "CupertinoIcons.Outlined.Goforward" -> CupertinoIcons.Outlined.Goforward
        "CupertinoIcons.Outlined.Graduationcap" -> CupertinoIcons.Outlined.Graduationcap
        "CupertinoIcons.Outlined.Grid" -> CupertinoIcons.Outlined.Grid
        "CupertinoIcons.Outlined.Hammer" -> CupertinoIcons.Outlined.Hammer
        "CupertinoIcons.Outlined.HandDraw" -> CupertinoIcons.Outlined.HandDraw
        "CupertinoIcons.Outlined.HandPointUp" -> CupertinoIcons.Outlined.HandPointUp
        "CupertinoIcons.Outlined.HandPointUpLeft" -> CupertinoIcons.Outlined.HandPointUpLeft
        "CupertinoIcons.Outlined.HandRaised" -> CupertinoIcons.Outlined.HandRaised
        "CupertinoIcons.Outlined.HandRaisedSlash" -> CupertinoIcons.Outlined.HandRaisedSlash
        "CupertinoIcons.Outlined.HandTap" -> CupertinoIcons.Outlined.HandTap
        "CupertinoIcons.Outlined.HandThumbsdown" -> CupertinoIcons.Outlined.HandThumbsdown
        "CupertinoIcons.Outlined.HandThumbsup" -> CupertinoIcons.Outlined.HandThumbsup
        "CupertinoIcons.Outlined.HandWave" -> CupertinoIcons.Outlined.HandWave
        "CupertinoIcons.Outlined.HandsSparkles" -> CupertinoIcons.Outlined.HandsSparkles
        "CupertinoIcons.Outlined.Headphones" -> CupertinoIcons.Outlined.Headphones
        "CupertinoIcons.Outlined.HeadphonesCircle" -> CupertinoIcons.Outlined.HeadphonesCircle
        "CupertinoIcons.Outlined.Heart" -> CupertinoIcons.Outlined.Heart
        "CupertinoIcons.Outlined.HeartCircle" -> CupertinoIcons.Outlined.HeartCircle
        "CupertinoIcons.Outlined.HeartSlash" -> CupertinoIcons.Outlined.HeartSlash
        "CupertinoIcons.Outlined.HeartTextSquare" -> CupertinoIcons.Outlined.HeartTextSquare
        "CupertinoIcons.Outlined.Hifispeaker" -> CupertinoIcons.Outlined.Hifispeaker
        "CupertinoIcons.Outlined.Highlighter" -> CupertinoIcons.Outlined.Highlighter
        "CupertinoIcons.Outlined.Homekit" -> CupertinoIcons.Outlined.Homekit
        "CupertinoIcons.Outlined.Homepod" -> CupertinoIcons.Outlined.Homepod
        "CupertinoIcons.Outlined.Homepodmini" -> CupertinoIcons.Outlined.Homepodmini
        "CupertinoIcons.Outlined.Hourglass" -> CupertinoIcons.Outlined.Hourglass
        "CupertinoIcons.Outlined.House" -> CupertinoIcons.Outlined.House
        "CupertinoIcons.Outlined.Hryvniasign" -> CupertinoIcons.Outlined.Hryvniasign
        "CupertinoIcons.Outlined.Icloud" -> CupertinoIcons.Outlined.Icloud
        "CupertinoIcons.Outlined.IcloudAndArrowDown" -> CupertinoIcons.Outlined.IcloudAndArrowDown
        "CupertinoIcons.Outlined.IcloudAndArrowUp" -> CupertinoIcons.Outlined.IcloudAndArrowUp
        "CupertinoIcons.Outlined.Infinity" -> CupertinoIcons.Outlined.Infinity
        "CupertinoIcons.Outlined.Info" -> CupertinoIcons.Outlined.Info
        "CupertinoIcons.Outlined.InfoBubble" -> CupertinoIcons.Outlined.InfoBubble
        "CupertinoIcons.Outlined.InfoCircle" -> CupertinoIcons.Outlined.InfoCircle
        "CupertinoIcons.Outlined.InfoSquare" -> CupertinoIcons.Outlined.InfoSquare
        "CupertinoIcons.Outlined.Ipad" -> CupertinoIcons.Outlined.Ipad
        "CupertinoIcons.Outlined.IpadAndIphone" -> CupertinoIcons.Outlined.IpadAndIphone
        "CupertinoIcons.Outlined.IpadHomebutton" -> CupertinoIcons.Outlined.IpadHomebutton
        "CupertinoIcons.Outlined.Iphone" -> CupertinoIcons.Outlined.Iphone
        "CupertinoIcons.Outlined.IphoneBadgePlay" -> CupertinoIcons.Outlined.IphoneBadgePlay
        "CupertinoIcons.Outlined.IphoneHomebutton" -> CupertinoIcons.Outlined.IphoneHomebutton
        "CupertinoIcons.Outlined.IphoneHomebuttonRadiowavesLeftAndRight" -> CupertinoIcons.Outlined.IphoneHomebuttonRadiowavesLeftAndRight
        "CupertinoIcons.Outlined.IphoneRadiowavesLeftAndRight" -> CupertinoIcons.Outlined.IphoneRadiowavesLeftAndRight
        "CupertinoIcons.Outlined.Key" -> CupertinoIcons.Outlined.Key
        "CupertinoIcons.Outlined.KeyIcloud" -> CupertinoIcons.Outlined.KeyIcloud
        "CupertinoIcons.Outlined.Keyboard" -> CupertinoIcons.Outlined.Keyboard
        "CupertinoIcons.Outlined.Lanyardcard" -> CupertinoIcons.Outlined.Lanyardcard
        "CupertinoIcons.Outlined.Laptopcomputer" -> CupertinoIcons.Outlined.Laptopcomputer
        "CupertinoIcons.Outlined.LaptopcomputerAndIpad" -> CupertinoIcons.Outlined.LaptopcomputerAndIpad
        "CupertinoIcons.Outlined.LaptopcomputerAndIphone" -> CupertinoIcons.Outlined.LaptopcomputerAndIphone
        "CupertinoIcons.Outlined.Leaf" -> CupertinoIcons.Outlined.Leaf
        "CupertinoIcons.Outlined.Level" -> CupertinoIcons.Outlined.Level
        "CupertinoIcons.Outlined.Lifepreserver" -> CupertinoIcons.Outlined.Lifepreserver
        "CupertinoIcons.Outlined.LightBeaconMax" -> CupertinoIcons.Outlined.LightBeaconMax
        "CupertinoIcons.Outlined.LightMax" -> CupertinoIcons.Outlined.LightMax
        "CupertinoIcons.Outlined.LightMin" -> CupertinoIcons.Outlined.LightMin
        "CupertinoIcons.Outlined.Lightbulb" -> CupertinoIcons.Outlined.Lightbulb
        "CupertinoIcons.Outlined.LightbulbSlash" -> CupertinoIcons.Outlined.LightbulbSlash
        "CupertinoIcons.Outlined.Link" -> CupertinoIcons.Outlined.Link
        "CupertinoIcons.Outlined.LinkBadgePlus" -> CupertinoIcons.Outlined.LinkBadgePlus
        "CupertinoIcons.Outlined.LinkCircle" -> CupertinoIcons.Outlined.LinkCircle
        "CupertinoIcons.Outlined.Lirasign" -> CupertinoIcons.Outlined.Lirasign
        "CupertinoIcons.Outlined.ListBullet" -> CupertinoIcons.Outlined.ListBullet
        "CupertinoIcons.Outlined.ListBulletCircle" -> CupertinoIcons.Outlined.ListBulletCircle
        "CupertinoIcons.Outlined.ListBulletClipboard" -> CupertinoIcons.Outlined.ListBulletClipboard
        "CupertinoIcons.Outlined.ListBulletIndent" -> CupertinoIcons.Outlined.ListBulletIndent
        "CupertinoIcons.Outlined.ListClipboard" -> CupertinoIcons.Outlined.ListClipboard
        "CupertinoIcons.Outlined.ListNumber" -> CupertinoIcons.Outlined.ListNumber
        "CupertinoIcons.Outlined.Livephoto" -> CupertinoIcons.Outlined.Livephoto
        "CupertinoIcons.Outlined.Location" -> CupertinoIcons.Outlined.Location
        "CupertinoIcons.Outlined.Lock" -> CupertinoIcons.Outlined.Lock
        "CupertinoIcons.Outlined.LockCircle" -> CupertinoIcons.Outlined.LockCircle
        "CupertinoIcons.Outlined.LockOpen" -> CupertinoIcons.Outlined.LockOpen
        "CupertinoIcons.Outlined.LockSlash" -> CupertinoIcons.Outlined.LockSlash
        "CupertinoIcons.Outlined.Macwindow" -> CupertinoIcons.Outlined.Macwindow
        "CupertinoIcons.Outlined.MacwindowBadgePlus" -> CupertinoIcons.Outlined.MacwindowBadgePlus
        "CupertinoIcons.Outlined.Magazine" -> CupertinoIcons.Outlined.Magazine
        "CupertinoIcons.Outlined.MagnifyingGlass" -> CupertinoIcons.Outlined.MagnifyingGlass
        "CupertinoIcons.Outlined.Mail" -> CupertinoIcons.Outlined.Mail
        "CupertinoIcons.Outlined.MailStack" -> CupertinoIcons.Outlined.MailStack
        "CupertinoIcons.Outlined.Map" -> CupertinoIcons.Outlined.Map
        "CupertinoIcons.Outlined.Mappin" -> CupertinoIcons.Outlined.Mappin
        "CupertinoIcons.Outlined.MappinAndEllipse" -> CupertinoIcons.Outlined.MappinAndEllipse
        "CupertinoIcons.Outlined.MappinSlash" -> CupertinoIcons.Outlined.MappinSlash
        "CupertinoIcons.Outlined.Medal" -> CupertinoIcons.Outlined.Medal
        "CupertinoIcons.Outlined.Megaphone" -> CupertinoIcons.Outlined.Megaphone
        "CupertinoIcons.Outlined.Memories" -> CupertinoIcons.Outlined.Memories
        "CupertinoIcons.Outlined.MenubarRectangle" -> CupertinoIcons.Outlined.MenubarRectangle
        "CupertinoIcons.Outlined.Menucard" -> CupertinoIcons.Outlined.Menucard
        "CupertinoIcons.Outlined.Message" -> CupertinoIcons.Outlined.Message
        "CupertinoIcons.Outlined.MessageBadge" -> CupertinoIcons.Outlined.MessageBadge
        "CupertinoIcons.Outlined.Mic" -> CupertinoIcons.Outlined.Mic
        "CupertinoIcons.Outlined.MicSlash" -> CupertinoIcons.Outlined.MicSlash
        "CupertinoIcons.Outlined.Minus" -> CupertinoIcons.Outlined.Minus
        "CupertinoIcons.Outlined.MinusCircle" -> CupertinoIcons.Outlined.MinusCircle
        "CupertinoIcons.Outlined.MinusMagnifyingglass" -> CupertinoIcons.Outlined.MinusMagnifyingglass
        "CupertinoIcons.Outlined.Moon" -> CupertinoIcons.Outlined.Moon
        "CupertinoIcons.Outlined.MoonStars" -> CupertinoIcons.Outlined.MoonStars
        "CupertinoIcons.Outlined.Mount" -> CupertinoIcons.Outlined.Mount
        "CupertinoIcons.Outlined.Multiply" -> CupertinoIcons.Outlined.Multiply
        "CupertinoIcons.Outlined.MusicMic" -> CupertinoIcons.Outlined.MusicMic
        "CupertinoIcons.Outlined.MusicNote" -> CupertinoIcons.Outlined.MusicNote
        "CupertinoIcons.Outlined.MusicNoteList" -> CupertinoIcons.Outlined.MusicNoteList
        "CupertinoIcons.Outlined.MusicQuarternote3" -> CupertinoIcons.Outlined.MusicQuarternote3
        "CupertinoIcons.Outlined.Network" -> CupertinoIcons.Outlined.Network
        "CupertinoIcons.Outlined.Newspaper" -> CupertinoIcons.Outlined.Newspaper
        "CupertinoIcons.Outlined.Nosign" -> CupertinoIcons.Outlined.Nosign
        "CupertinoIcons.Outlined.NoteText" -> CupertinoIcons.Outlined.NoteText
        "CupertinoIcons.Outlined.NoteTextBadgePlus" -> CupertinoIcons.Outlined.NoteTextBadgePlus
        "CupertinoIcons.Outlined.Number" -> CupertinoIcons.Outlined.Number
        "CupertinoIcons.Outlined.Opticaldisc" -> CupertinoIcons.Outlined.Opticaldisc
        "CupertinoIcons.Outlined.Option" -> CupertinoIcons.Outlined.Option
        "CupertinoIcons.Outlined.Paintbrush" -> CupertinoIcons.Outlined.Paintbrush
        "CupertinoIcons.Outlined.PaintbrushPointed" -> CupertinoIcons.Outlined.PaintbrushPointed
        "CupertinoIcons.Outlined.Paintpalette" -> CupertinoIcons.Outlined.Paintpalette
        "CupertinoIcons.Outlined.Paperclip" -> CupertinoIcons.Outlined.Paperclip
        "CupertinoIcons.Outlined.PaperclipBadgeEllipsis" -> CupertinoIcons.Outlined.PaperclipBadgeEllipsis
        "CupertinoIcons.Outlined.PaperclipCircle" -> CupertinoIcons.Outlined.PaperclipCircle
        "CupertinoIcons.Outlined.Paperplane" -> CupertinoIcons.Outlined.Paperplane
        "CupertinoIcons.Outlined.Paragraphsign" -> CupertinoIcons.Outlined.Paragraphsign
        "CupertinoIcons.Outlined.PartyPopper" -> CupertinoIcons.Outlined.PartyPopper
        "CupertinoIcons.Outlined.Pause" -> CupertinoIcons.Outlined.Pause
        "CupertinoIcons.Outlined.PauseCircle" -> CupertinoIcons.Outlined.PauseCircle
        "CupertinoIcons.Outlined.Pawprint" -> CupertinoIcons.Outlined.Pawprint
        "CupertinoIcons.Outlined.Pencil" -> CupertinoIcons.Outlined.Pencil
        "CupertinoIcons.Outlined.PencilCircle" -> CupertinoIcons.Outlined.PencilCircle
        "CupertinoIcons.Outlined.PencilTipCropCircle" -> CupertinoIcons.Outlined.PencilTipCropCircle
        "CupertinoIcons.Outlined.Percent" -> CupertinoIcons.Outlined.Percent
        "CupertinoIcons.Outlined.Person" -> CupertinoIcons.Outlined.Person
        "CupertinoIcons.Outlined.Person2" -> CupertinoIcons.Outlined.Person2
        "CupertinoIcons.Outlined.PersonAndBackgroundDotted" -> CupertinoIcons.Outlined.PersonAndBackgroundDotted
        "CupertinoIcons.Outlined.PersonCircle" -> CupertinoIcons.Outlined.PersonCircle
        "CupertinoIcons.Outlined.PersonCropCircle" -> CupertinoIcons.Outlined.PersonCropCircle
        "CupertinoIcons.Outlined.PersonCropCircleBadgeMinus" -> CupertinoIcons.Outlined.PersonCropCircleBadgeMinus
        "CupertinoIcons.Outlined.PersonCropCircleBadgePlus" -> CupertinoIcons.Outlined.PersonCropCircleBadgePlus
        "CupertinoIcons.Outlined.PersonCropSquare" -> CupertinoIcons.Outlined.PersonCropSquare
        "CupertinoIcons.Outlined.PersonIcloud" -> CupertinoIcons.Outlined.PersonIcloud
        "CupertinoIcons.Outlined.PersonTextRectangle" -> CupertinoIcons.Outlined.PersonTextRectangle
        "CupertinoIcons.Outlined.PersonWave2" -> CupertinoIcons.Outlined.PersonWave2
        "CupertinoIcons.Outlined.Personalhotspot" -> CupertinoIcons.Outlined.Personalhotspot
        "CupertinoIcons.Outlined.Phone" -> CupertinoIcons.Outlined.Phone
        "CupertinoIcons.Outlined.PhoneAndWaveform" -> CupertinoIcons.Outlined.PhoneAndWaveform
        "CupertinoIcons.Outlined.PhoneArrowDownLeft" -> CupertinoIcons.Outlined.PhoneArrowDownLeft
        "CupertinoIcons.Outlined.PhoneArrowUpRight" -> CupertinoIcons.Outlined.PhoneArrowUpRight
        "CupertinoIcons.Outlined.PhoneBadgePlus" -> CupertinoIcons.Outlined.PhoneBadgePlus
        "CupertinoIcons.Outlined.PhoneCircle" -> CupertinoIcons.Outlined.PhoneCircle
        "CupertinoIcons.Outlined.PhoneConnection" -> CupertinoIcons.Outlined.PhoneConnection
        "CupertinoIcons.Outlined.Photo" -> CupertinoIcons.Outlined.Photo
        "CupertinoIcons.Outlined.PhotoStack" -> CupertinoIcons.Outlined.PhotoStack
        "CupertinoIcons.Outlined.PhotoTv" -> CupertinoIcons.Outlined.PhotoTv
        "CupertinoIcons.Outlined.Pill" -> CupertinoIcons.Outlined.Pill
        "CupertinoIcons.Outlined.Pin" -> CupertinoIcons.Outlined.Pin
        "CupertinoIcons.Outlined.PinCircle" -> CupertinoIcons.Outlined.PinCircle
        "CupertinoIcons.Outlined.PinSlash" -> CupertinoIcons.Outlined.PinSlash
        "CupertinoIcons.Outlined.Pip" -> CupertinoIcons.Outlined.Pip
        "CupertinoIcons.Outlined.PipEnter" -> CupertinoIcons.Outlined.PipEnter
        "CupertinoIcons.Outlined.PipExit" -> CupertinoIcons.Outlined.PipExit
        "CupertinoIcons.Outlined.Play" -> CupertinoIcons.Outlined.Play
        "CupertinoIcons.Outlined.PlayCircle" -> CupertinoIcons.Outlined.PlayCircle
        "CupertinoIcons.Outlined.PlayDisplay" -> CupertinoIcons.Outlined.PlayDisplay
        "CupertinoIcons.Outlined.Plus" -> CupertinoIcons.Outlined.Plus
        "CupertinoIcons.Outlined.PlusApp" -> CupertinoIcons.Outlined.PlusApp
        "CupertinoIcons.Outlined.PlusBubble" -> CupertinoIcons.Outlined.PlusBubble
        "CupertinoIcons.Outlined.PlusCircle" -> CupertinoIcons.Outlined.PlusCircle
        "CupertinoIcons.Outlined.PlusMagnifyingglass" -> CupertinoIcons.Outlined.PlusMagnifyingglass
        "CupertinoIcons.Outlined.PlusMessage" -> CupertinoIcons.Outlined.PlusMessage
        "CupertinoIcons.Outlined.PlusSquare" -> CupertinoIcons.Outlined.PlusSquare
        "CupertinoIcons.Outlined.PlusViewfinder" -> CupertinoIcons.Outlined.PlusViewfinder
        "CupertinoIcons.Outlined.Popcorn" -> CupertinoIcons.Outlined.Popcorn
        "CupertinoIcons.Outlined.Power" -> CupertinoIcons.Outlined.Power
        "CupertinoIcons.Outlined.PowerCircle" -> CupertinoIcons.Outlined.PowerCircle
        "CupertinoIcons.Outlined.Printer" -> CupertinoIcons.Outlined.Printer
        "CupertinoIcons.Outlined.Puzzlepiece" -> CupertinoIcons.Outlined.Puzzlepiece
        "CupertinoIcons.Outlined.PuzzlepieceExtension" -> CupertinoIcons.Outlined.PuzzlepieceExtension
        "CupertinoIcons.Outlined.Qrcode" -> CupertinoIcons.Outlined.Qrcode
        "CupertinoIcons.Outlined.QrcodeViewfinder" -> CupertinoIcons.Outlined.QrcodeViewfinder
        "CupertinoIcons.Outlined.Questionmark" -> CupertinoIcons.Outlined.Questionmark
        "CupertinoIcons.Outlined.QuestionmarkApp" -> CupertinoIcons.Outlined.QuestionmarkApp
        "CupertinoIcons.Outlined.QuestionmarkCircle" -> CupertinoIcons.Outlined.QuestionmarkCircle
        "CupertinoIcons.Outlined.QuestionmarkFolder" -> CupertinoIcons.Outlined.QuestionmarkFolder
        "CupertinoIcons.Outlined.QuestionmarkSquare" -> CupertinoIcons.Outlined.QuestionmarkSquare
        "CupertinoIcons.Outlined.QuoteClosing" -> CupertinoIcons.Outlined.QuoteClosing
        "CupertinoIcons.Outlined.QuoteOpening" -> CupertinoIcons.Outlined.QuoteOpening
        "CupertinoIcons.Outlined.Rays" -> CupertinoIcons.Outlined.Rays
        "CupertinoIcons.Outlined.RecordCircle" -> CupertinoIcons.Outlined.RecordCircle
        "CupertinoIcons.Outlined.Recordingtape" -> CupertinoIcons.Outlined.Recordingtape
        "CupertinoIcons.Outlined.RectangleArrowtriangle2Outward" -> CupertinoIcons.Outlined.RectangleArrowtriangle2Outward
        "CupertinoIcons.Outlined.RectangleConnectedToLineBelow" -> CupertinoIcons.Outlined.RectangleConnectedToLineBelow
        "CupertinoIcons.Outlined.RectanglePortraitAndArrowForward" -> CupertinoIcons.Outlined.RectanglePortraitAndArrowForward
        "CupertinoIcons.Outlined.RectanglePortraitArrowtriangle2Outward" -> CupertinoIcons.Outlined.RectanglePortraitArrowtriangle2Outward
        "CupertinoIcons.Outlined.RectangleStack" -> CupertinoIcons.Outlined.RectangleStack
        "CupertinoIcons.Outlined.Repeat" -> CupertinoIcons.Outlined.Repeat
        "CupertinoIcons.Outlined.Rosette" -> CupertinoIcons.Outlined.Rosette
        "CupertinoIcons.Outlined.Rotate3d" -> CupertinoIcons.Outlined.Rotate3d
        "CupertinoIcons.Outlined.RotateLeft" -> CupertinoIcons.Outlined.RotateLeft
        "CupertinoIcons.Outlined.RotateRight" -> CupertinoIcons.Outlined.RotateRight
        "CupertinoIcons.Outlined.Rublesign" -> CupertinoIcons.Outlined.Rublesign
        "CupertinoIcons.Outlined.Ruler" -> CupertinoIcons.Outlined.Ruler
        "CupertinoIcons.Outlined.Safari" -> CupertinoIcons.Outlined.Safari
        "CupertinoIcons.Outlined.Scalemass" -> CupertinoIcons.Outlined.Scalemass
        "CupertinoIcons.Outlined.Scissors" -> CupertinoIcons.Outlined.Scissors
        "CupertinoIcons.Outlined.Scope" -> CupertinoIcons.Outlined.Scope
        "CupertinoIcons.Outlined.Scribble" -> CupertinoIcons.Outlined.Scribble
        "CupertinoIcons.Outlined.ScribbleVariable" -> CupertinoIcons.Outlined.ScribbleVariable
        "CupertinoIcons.Outlined.Scroll" -> CupertinoIcons.Outlined.Scroll
        "CupertinoIcons.Outlined.ServerRack" -> CupertinoIcons.Outlined.ServerRack
        "CupertinoIcons.Outlined.Shareplay" -> CupertinoIcons.Outlined.Shareplay
        "CupertinoIcons.Outlined.ShareplaySlash" -> CupertinoIcons.Outlined.ShareplaySlash
        "CupertinoIcons.Outlined.ShazamLogo" -> CupertinoIcons.Outlined.ShazamLogo
        "CupertinoIcons.Outlined.Shield" -> CupertinoIcons.Outlined.Shield
        "CupertinoIcons.Outlined.ShieldSlash" -> CupertinoIcons.Outlined.ShieldSlash
        "CupertinoIcons.Outlined.Shippingbox" -> CupertinoIcons.Outlined.Shippingbox
        "CupertinoIcons.Outlined.Shuffle" -> CupertinoIcons.Outlined.Shuffle
        "CupertinoIcons.Outlined.SidebarLeft" -> CupertinoIcons.Outlined.SidebarLeft
        "CupertinoIcons.Outlined.SidebarRight" -> CupertinoIcons.Outlined.SidebarRight
        "CupertinoIcons.Outlined.Simcard" -> CupertinoIcons.Outlined.Simcard
        "CupertinoIcons.Outlined.Skew" -> CupertinoIcons.Outlined.Skew
        "CupertinoIcons.Outlined.SliderHorizontal3" -> CupertinoIcons.Outlined.SliderHorizontal3
        "CupertinoIcons.Outlined.SliderVertical3" -> CupertinoIcons.Outlined.SliderVertical3
        "CupertinoIcons.Outlined.Snowflake" -> CupertinoIcons.Outlined.Snowflake
        "CupertinoIcons.Outlined.Soccerball" -> CupertinoIcons.Outlined.Soccerball
        "CupertinoIcons.Outlined.Space" -> CupertinoIcons.Outlined.Space
        "CupertinoIcons.Outlined.Sparkle" -> CupertinoIcons.Outlined.Sparkle
        "CupertinoIcons.Outlined.Sparkles" -> CupertinoIcons.Outlined.Sparkles
        "CupertinoIcons.Outlined.Speaker" -> CupertinoIcons.Outlined.Speaker
        "CupertinoIcons.Outlined.SpeakerMinus" -> CupertinoIcons.Outlined.SpeakerMinus
        "CupertinoIcons.Outlined.SpeakerPlus" -> CupertinoIcons.Outlined.SpeakerPlus
        "CupertinoIcons.Outlined.SpeakerSlash" -> CupertinoIcons.Outlined.SpeakerSlash
        "CupertinoIcons.Outlined.SpeakerWave2" -> CupertinoIcons.Outlined.SpeakerWave2
        "CupertinoIcons.Outlined.Speedometer" -> CupertinoIcons.Outlined.Speedometer
        "CupertinoIcons.Outlined.Square3Layers3dDownLeft" -> CupertinoIcons.Outlined.Square3Layers3dDownLeft
        "CupertinoIcons.Outlined.Square3Layers3dDownRight" -> CupertinoIcons.Outlined.Square3Layers3dDownRight
        "CupertinoIcons.Outlined.SquareAndArrowUp" -> CupertinoIcons.Outlined.SquareAndArrowUp
        "CupertinoIcons.Outlined.SquareAndPencil" -> CupertinoIcons.Outlined.SquareAndPencil
        "CupertinoIcons.Outlined.SquareOnSquare" -> CupertinoIcons.Outlined.SquareOnSquare
        "CupertinoIcons.Outlined.SquareSplit1x2" -> CupertinoIcons.Outlined.SquareSplit1x2
        "CupertinoIcons.Outlined.SquareSplit2x1" -> CupertinoIcons.Outlined.SquareSplit2x1
        "CupertinoIcons.Outlined.SquareStack" -> CupertinoIcons.Outlined.SquareStack
        "CupertinoIcons.Outlined.SquareStack3dUp" -> CupertinoIcons.Outlined.SquareStack3dUp
        "CupertinoIcons.Outlined.Star" -> CupertinoIcons.Outlined.Star
        "CupertinoIcons.Outlined.StarSlash" -> CupertinoIcons.Outlined.StarSlash
        "CupertinoIcons.Outlined.Staroflife" -> CupertinoIcons.Outlined.Staroflife
        "CupertinoIcons.Outlined.Sterlingsign" -> CupertinoIcons.Outlined.Sterlingsign
        "CupertinoIcons.Outlined.Stethoscope" -> CupertinoIcons.Outlined.Stethoscope
        "CupertinoIcons.Outlined.Stop" -> CupertinoIcons.Outlined.Stop
        "CupertinoIcons.Outlined.StopCircle" -> CupertinoIcons.Outlined.StopCircle
        "CupertinoIcons.Outlined.Suitcase" -> CupertinoIcons.Outlined.Suitcase
        "CupertinoIcons.Outlined.Sum" -> CupertinoIcons.Outlined.Sum
        "CupertinoIcons.Outlined.SunMax" -> CupertinoIcons.Outlined.SunMax
        "CupertinoIcons.Outlined.Swift" -> CupertinoIcons.Outlined.Swift
        "CupertinoIcons.Outlined.Tag" -> CupertinoIcons.Outlined.Tag
        "CupertinoIcons.Outlined.Target" -> CupertinoIcons.Outlined.Target
        "CupertinoIcons.Outlined.TennisRacket" -> CupertinoIcons.Outlined.TennisRacket
        "CupertinoIcons.Outlined.Terminal" -> CupertinoIcons.Outlined.Terminal
        "CupertinoIcons.Outlined.TextBubble" -> CupertinoIcons.Outlined.TextBubble
        "CupertinoIcons.Outlined.TextMagnifyingglass" -> CupertinoIcons.Outlined.TextMagnifyingglass
        "CupertinoIcons.Outlined.Theatermasks" -> CupertinoIcons.Outlined.Theatermasks
        "CupertinoIcons.Outlined.Timer" -> CupertinoIcons.Outlined.Timer
        "CupertinoIcons.Outlined.Touchid" -> CupertinoIcons.Outlined.Touchid
        "CupertinoIcons.Outlined.Trash" -> CupertinoIcons.Outlined.Trash
        "CupertinoIcons.Outlined.TrashSlash" -> CupertinoIcons.Outlined.TrashSlash
        "CupertinoIcons.Outlined.TrayAndArrowDown" -> CupertinoIcons.Outlined.TrayAndArrowDown
        "CupertinoIcons.Outlined.TrayAndArrowUp" -> CupertinoIcons.Outlined.TrayAndArrowUp
        "CupertinoIcons.Outlined.Trophy" -> CupertinoIcons.Outlined.Trophy
        "CupertinoIcons.Outlined.Tshirt" -> CupertinoIcons.Outlined.Tshirt
        "CupertinoIcons.Outlined.Tv" -> CupertinoIcons.Outlined.Tv
        "CupertinoIcons.Outlined.Umbrella" -> CupertinoIcons.Outlined.Umbrella
        "CupertinoIcons.Outlined.Video" -> CupertinoIcons.Outlined.Video
        "CupertinoIcons.Outlined.VideoCircle" -> CupertinoIcons.Outlined.VideoCircle
        "CupertinoIcons.Outlined.VideoSlash" -> CupertinoIcons.Outlined.VideoSlash
        "CupertinoIcons.Outlined.Volleyball" -> CupertinoIcons.Outlined.Volleyball
        "CupertinoIcons.Outlined.WalletPass" -> CupertinoIcons.Outlined.WalletPass
        "CupertinoIcons.Outlined.WandAndStars" -> CupertinoIcons.Outlined.WandAndStars
        "CupertinoIcons.Outlined.WandAndStarsInverse" -> CupertinoIcons.Outlined.WandAndStarsInverse
        "CupertinoIcons.Outlined.Waveform" -> CupertinoIcons.Outlined.Waveform
        "CupertinoIcons.Outlined.WaveformAndMagnifyingglass" -> CupertinoIcons.Outlined.WaveformAndMagnifyingglass
        "CupertinoIcons.Outlined.WaveformAndMic" -> CupertinoIcons.Outlined.WaveformAndMic
        "CupertinoIcons.Outlined.WaveformPathEcg" -> CupertinoIcons.Outlined.WaveformPathEcg
        "CupertinoIcons.Outlined.WebCamera" -> CupertinoIcons.Outlined.WebCamera
        "CupertinoIcons.Outlined.Wifi" -> CupertinoIcons.Outlined.Wifi
        "CupertinoIcons.Outlined.WifiExclamationmark" -> CupertinoIcons.Outlined.WifiExclamationmark
        "CupertinoIcons.Outlined.WifiRouter" -> CupertinoIcons.Outlined.WifiRouter
        "CupertinoIcons.Outlined.WifiSlash" -> CupertinoIcons.Outlined.WifiSlash
        "CupertinoIcons.Outlined.Wind" -> CupertinoIcons.Outlined.Wind
        "CupertinoIcons.Outlined.Wineglass" -> CupertinoIcons.Outlined.Wineglass
        "CupertinoIcons.Outlined.WrenchAndScrewdriver" -> CupertinoIcons.Outlined.WrenchAndScrewdriver
        "CupertinoIcons.Outlined.Xmark" -> CupertinoIcons.Outlined.Xmark
        "CupertinoIcons.Outlined.XmarkApp" -> CupertinoIcons.Outlined.XmarkApp
        "CupertinoIcons.Outlined.XmarkBin" -> CupertinoIcons.Outlined.XmarkBin
        "CupertinoIcons.Outlined.XmarkCircle" -> CupertinoIcons.Outlined.XmarkCircle
        "CupertinoIcons.Outlined.XmarkIcloud" -> CupertinoIcons.Outlined.XmarkIcloud
        "CupertinoIcons.Outlined.XmarkSeal" -> CupertinoIcons.Outlined.XmarkSeal
        "CupertinoIcons.Outlined.XmarkShield" -> CupertinoIcons.Outlined.XmarkShield
        "CupertinoIcons.Outlined.Yensign" -> CupertinoIcons.Outlined.Yensign
        "CupertinoIcons.Outlined.Zzz" -> CupertinoIcons.Outlined.Zzz
        "CupertinoIcons.Outlined._4kTv" -> CupertinoIcons.Outlined._4kTv
        else -> null
    }
}
