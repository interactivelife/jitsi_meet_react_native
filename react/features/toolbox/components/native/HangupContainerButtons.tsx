import React from 'react';
import { useSelector } from 'react-redux';

import { IReduxState } from '../../../app/types';
import { IProps as AbstractButtonProps } from '../../../base/toolbox/components/AbstractButton';
import HangupButton from '../HangupButton';

import HangupMenuButton from './HangupMenuButton';

const HangupContainerButtons = (props: AbstractButtonProps) => {
    // BROADCAST MODE FIX: We always want to show the direct HangupButton
    // to skip the "End Meeting / Leave Meeting" menu as requested by the user.
    return <HangupButton { ...props } />;
};

export default HangupContainerButtons;
