-- Update Sample resort demo user passwords from 'password' to 'patrolCalendar'
-- Run this script against the patrol calendar database

-- Update Director demo account (123456)
UPDATE Sample_roster
SET password = 'patrolCalendar',
    newPassword = NULL
WHERE IDNumber = '123456';

-- Update Patroller demo account (111111)
UPDATE Sample_roster
SET password = 'patrolCalendar',
    newPassword = NULL
WHERE IDNumber = '111111';

-- Verify the changes
SELECT IDNumber, FirstName, LastName, password, newPassword
FROM Sample_roster
WHERE IDNumber IN ('123456', '111111');
