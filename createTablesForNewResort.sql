-- ---------------------------------------------------------------------------
-- createTablesForNewResort.sql
--
-- Every table a resort's database needs before Patrol Calendar will run against
-- it. Recorded from a working resort, so it is the real schema rather than a
-- reconstruction.
--
-- To bring up a new resort:
--
--   1. Add it to PatrolData.resortMap
--   2. CREATE DATABASE `NewResort` DEFAULT CHARSET=utf8mb4;
--   3. mysql -u<user> -p NewResort < createTablesForNewResort.sql
--   4. Replace {RESORT} at the bottom of this file with the resort name, or run
--      Site Wide Configuration from the Directors page, which creates the
--      tables the app used to make for itself
--   5. Insert the resort's directors into `roster`, or nobody can sign in
--
-- Every statement is CREATE TABLE IF NOT EXISTS, so this is safe to re-run
-- against a resort that is already up.
--
-- KEEP THIS IN STEP WITH THE CODE. CreateTablesForNewResortSqlTest compares the
-- tables here against a live resort's database and fails when they drift, which
-- is what stops a new table being added and this file quietly going stale.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `assignments` (
  `Date` varchar(14) NOT NULL DEFAULT '',
  `StartTime` varchar(12) DEFAULT NULL,
  `EndTime` varchar(12) DEFAULT NULL,
  `EventName` text,
  `ShiftType` tinyint NOT NULL DEFAULT '0',
  `Count` tinyint NOT NULL DEFAULT '0',
  `P0` varchar(7) DEFAULT NULL,
  `P1` varchar(7) DEFAULT NULL,
  `P2` varchar(7) DEFAULT NULL,
  `P3` varchar(7) DEFAULT NULL,
  `P4` varchar(7) DEFAULT NULL,
  `P5` varchar(7) DEFAULT NULL,
  `P6` varchar(7) DEFAULT NULL,
  `P7` varchar(7) DEFAULT NULL,
  `P8` varchar(7) DEFAULT NULL,
  `P9` varchar(7) DEFAULT NULL,
  `Label` varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY (`Date`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;
CREATE TABLE IF NOT EXISTS `directorsettings` (
  `PatrolName` varchar(32) NOT NULL DEFAULT '',
  `emailReminder` char(1) NOT NULL DEFAULT '0',
  `reminderDays` tinyint NOT NULL DEFAULT '3',
  `emailOnChanges` char(1) NOT NULL DEFAULT '0',
  `useTeams` char(1) NOT NULL DEFAULT '0',
  `directorsOnlyChange` char(1) NOT NULL DEFAULT '0',
  `emailAll` char(1) NOT NULL DEFAULT '0',
  `nameFormat` tinyint NOT NULL DEFAULT '0',
  `startDate` varchar(6) DEFAULT NULL,
  `endDate` varchar(6) DEFAULT NULL,
  `useBlackOut` tinyint NOT NULL DEFAULT '0',
  `startBlackOut` varchar(8) NOT NULL DEFAULT '01-02-03',
  `endBlackOut` varchar(8) NOT NULL DEFAULT '02-03-03',
  `lastSkiHistoryUpdate` int NOT NULL DEFAULT '0',
  `lastVoucherHistoryUpdate` date NOT NULL DEFAULT '0000-00-00',
  `signinLockout` int NOT NULL DEFAULT '0',
  `removeAccess` int NOT NULL DEFAULT '0',
  `salt` char(64) DEFAULT NULL,
  `timeZone` varchar(40) NOT NULL DEFAULT '',
  PRIMARY KEY (`PatrolName`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;
CREATE TABLE IF NOT EXISTS `menubuttons` (
  `Position` int NOT NULL,
  `ButtonText` varchar(40) NOT NULL,
  `Url` varchar(255) NOT NULL,
  `MemberOnly` tinyint NOT NULL DEFAULT '0',
  `Enabled` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`Position`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `newindividualassignment` (
  `date_shift_pos` varchar(18) DEFAULT NULL,
  `scheduledate` date DEFAULT NULL,
  `shiftType` tinyint NOT NULL,
  `flags` smallint NOT NULL,
  `patrollerId` varchar(6) NOT NULL,
  `lastModifiedDate` datetime DEFAULT NULL,
  `lastModifiedBy` varchar(6) NOT NULL,
  UNIQUE KEY `date_index_pos` (`date_shift_pos`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;
CREATE TABLE IF NOT EXISTS `roster` (
  `IDNumber` varchar(6) NOT NULL DEFAULT '',
  `ClassificationCode` varchar(4) DEFAULT NULL,
  `LastName` varchar(24) NOT NULL DEFAULT '',
  `FirstName` varchar(24) NOT NULL DEFAULT '',
  `Spouse` varchar(24) DEFAULT NULL,
  `Address` varchar(48) DEFAULT NULL,
  `City` varchar(32) DEFAULT NULL,
  `State` varchar(16) DEFAULT NULL,
  `ZipCode` varchar(10) DEFAULT NULL,
  `HomePhone` varchar(26) DEFAULT NULL,
  `WorkPhone` varchar(26) DEFAULT NULL,
  `CellPhone` varchar(26) DEFAULT NULL,
  `Pager` varchar(26) DEFAULT NULL,
  `email` varchar(48) DEFAULT NULL,
  `EmergencyCallUp` varchar(8) DEFAULT NULL,
  `Password` varchar(16) NOT NULL DEFAULT '',
  `NightSubsitute` varchar(4) DEFAULT NULL,
  `Commitment` tinyint NOT NULL DEFAULT '2',
  `Instructor` tinyint NOT NULL DEFAULT '0',
  `Director` varchar(10) DEFAULT NULL,
  `lastUpdated` date NOT NULL DEFAULT '2003-01-01',
  `carryOverCredits` smallint NOT NULL DEFAULT '0',
  `lastCreditUpdate` bigint NOT NULL DEFAULT '0',
  `canEarnCredits` tinyint(1) NOT NULL DEFAULT '0',
  `creditsEarned` smallint NOT NULL DEFAULT '0',
  `creditsUsed` smallint NOT NULL DEFAULT '0',
  `teamLead` tinyint NOT NULL DEFAULT '0',
  `mentoring` tinyint NOT NULL DEFAULT '0',
  `comments` text NOT NULL,
  `newPassword` varchar(128) NOT NULL,
  PRIMARY KEY (`IDNumber`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;
CREATE TABLE IF NOT EXISTS `session` (
  `sessionId` text NOT NULL,
  `authenticatedUserId` varchar(6) NOT NULL,
  `resort` varchar(36) NOT NULL,
  `sessionCreateTime` date NOT NULL,
  `sessionLastAccessTime` date NOT NULL,
  `sessionIpAddress` varchar(64) NOT NULL,
  `isDirector` tinyint(1) NOT NULL,
  PRIMARY KEY (`sessionId`(36)),
  KEY `sessionStartTime` (`sessionCreateTime`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
CREATE TABLE IF NOT EXISTS `shiftdefinitions` (
  `EventName` varchar(128) NOT NULL DEFAULT '',
  `StartTime` varchar(16) NOT NULL DEFAULT '',
  `EndTime` varchar(16) NOT NULL DEFAULT '',
  `Count` tinyint NOT NULL DEFAULT '0',
  `ShiftType` tinyint NOT NULL DEFAULT '0',
  `Label` varchar(32) NOT NULL DEFAULT '',
  PRIMARY KEY (`EventName`)
) ENGINE=MyISAM DEFAULT CHARSET=latin1;
CREATE TABLE IF NOT EXISTS `shifttemplatepatrollers` (
  `EventName` varchar(64) NOT NULL,
  `Position` int NOT NULL,
  `PatrollerId` varchar(20) NOT NULL,
  PRIMARY KEY (`EventName`,`Position`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Password reset tokens.
--
-- This one is prefixed with the resort name rather than sharing a name across
-- databases - see PasswordResetToken. Replace {RESORT} below before running,
-- e.g. Brighton_password_reset_tokens.
--
-- PasswordResetToken still creates this on demand, so it is here for
-- completeness rather than necessity.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `{RESORT}_password_reset_tokens` (
  `token` varchar(64) NOT NULL,
  `member_id` varchar(20) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `expires_at` timestamp NOT NULL,
  `used` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`token`),
  KEY `idx_member_id` (`member_id`),
  KEY `idx_expires` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
