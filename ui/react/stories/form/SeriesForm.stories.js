import React, { Component } from 'react';
import { SeriesForm, Series } from '../../src';
import { action } from '@storybook/addon-actions';

class SeriesFormStories extends Component {
    series = new Series();

    constructor(props) {
        super(props);
        this.afterChange = this.afterChange.bind(this);
    }

    afterChange(series) {
        action('SeriesBinderForm afterChange')(series);
    }

    render() {
        return (
            <div>
                <h3>SeriesForm</h3>
                <SeriesForm
                    series={this.series}
                    afterChange={this.afterChange} />
            </div>
        );
    }
}

export default SeriesFormStories;