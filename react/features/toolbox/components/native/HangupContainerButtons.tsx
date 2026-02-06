import React from 'react';
import { useSelector } from 'react-redux';

import { IReduxState } from '../../../app/types';
import { IProps as AbstractButtonProps } from '../../../base/toolbox/components/AbstractButton';
import HangupButton from '../HangupButton';

import HangupMenuButton from './HangupMenuButton';

const HangupContainerButtons = (props: AbstractButtonProps) => {
    const isCipherCall = useSelector((state: IReduxState) => state['features/base/config'].isCipherCall);

    // DEBUG LOG
    // @ts-ignore
    console.log(`[HangupContainerButtons] isCipherCall: ${isCipherCall}`);

    if (isCipherCall) {
        return <HangupMenuButton />;
    }

    return <HangupButton { ...props } />;
};

export default HangupContainerButtons;


